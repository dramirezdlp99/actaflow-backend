package com.actaflow.backend.expedientes.aplicacion;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.actaflow.backend.expedientes.plantillas.Documento;
import com.actaflow.backend.expedientes.plantillas.Lenguaje;
import com.actaflow.backend.expedientes.plantillas.Lenguaje.Analisis;
import com.actaflow.backend.expedientes.seguridad.Usuario;
import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.backend.plataforma.rpc.FalloRpc;
import com.actaflow.backend.plataforma.rpc.FalloRpc.Tipo;
import com.actaflow.contracts.common.v1.PageInfo;
import com.actaflow.contracts.templates.v1.GetPublishedTemplateResponse;
import com.actaflow.contracts.templates.v1.GetTemplateVersionResponse;
import com.actaflow.contracts.templates.v1.ListOrganizationTemplatesResponse;
import com.actaflow.contracts.templates.v1.ListPublishedTemplatesResponse;
import com.actaflow.contracts.templates.v1.ListTemplateVersionsResponse;
import com.actaflow.contracts.templates.v1.PreviewTemplateResponse;
import com.actaflow.contracts.templates.v1.PublishTemplateResponse;
import com.actaflow.contracts.templates.v1.SaveTemplateDraftRequest;
import com.actaflow.contracts.templates.v1.SaveTemplateDraftResponse;
import com.actaflow.contracts.templates.v1.Template;
import com.actaflow.contracts.templates.v1.TemplateCategory;
import com.actaflow.contracts.templates.v1.TemplateDiagnostic;
import com.actaflow.contracts.templates.v1.TemplateDiagnostics;
import com.actaflow.contracts.templates.v1.TemplateValue;
import com.actaflow.contracts.templates.v1.ValidateTemplateResponse;
import com.actaflow.contracts.templates.v1.VersionState;

/**
 * Plantillas juridicas. Leer la biblioteca publicada no exige sesion; todo lo demas es solo para
 * administradores, y editar o publicar, solo para la organizacion autora. Las plantillas del
 * sistema (sin organizacion) no las edita nadie: se usan como base de una plantilla propia.
 */
public final class ServicioPlantillas {

    /** Razon estable de {@code SaveTemplateDraft} cuando la fuente tiene errores. */
    public static final String ERROR_DE_SINTAXIS = "TEMPLATE_SYNTAX_ERROR";

    private static final String COLUMNAS = "p.id, p.slug, p.organization_id, v.version, v.state, v.title,"
            + " v.description, v.category, v.source, v.updated_at, v.published_at";

    private final Bd bd;
    private final Clock reloj;

    public ServicioPlantillas(Bd bd, Clock reloj) {
        this.bd = bd;
        this.reloj = reloj;
    }

    // ----------------------------------------------------------------------------- publicas

    /** Ultima version publicada de cada plantilla, por titulo. Sin la fuente. */
    public ListPublishedTemplatesResponse listarPublicadas(int tamano, String token, TemplateCategory categoria) {
        int desde = Filas.desplazamiento(token);
        String filtro = categoria == TemplateCategory.TEMPLATE_CATEGORY_UNSPECIFIED ? null : texto(categoria);
        return bd.transaccion(c -> {
            List<Template> filas = Bd.varios(c, "select " + COLUMNAS + " from plantilla p join plantilla_version v"
                    + " on v.plantilla_id = p.id and v.state = 'PUBLISHED'"
                    + " where cast(? as varchar) is null or v.category = cast(? as varchar)"
                    + " order by v.title, p.id limit ? offset ?", f -> plantilla(f, false), filtro, filtro,
                    tamano + 1, desde);
            ListPublishedTemplatesResponse.Builder respuesta = ListPublishedTemplatesResponse.newBuilder();
            filas.stream().limit(tamano).forEach(respuesta::addTemplates);
            String siguiente = filas.size() > tamano ? Filas.token(desde + tamano) : "";
            return respuesta.setPageInfo(PageInfo.newBuilder().setNextPageToken(siguiente)).build();
        });
    }

    public GetPublishedTemplateResponse publicada(String slug) {
        return bd.transaccion(c -> GetPublishedTemplateResponse.newBuilder()
                .setTemplate(Bd.uno(c, "select " + COLUMNAS + " from plantilla p join plantilla_version v"
                        + " on v.plantilla_id = p.id and v.state = 'PUBLISHED' where p.slug = ?",
                        f -> plantilla(f, true), slug)
                        .orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO,
                                "No hay una plantilla publicada con ese nombre")))
                .build());
    }

    // ----------------------------------------------------------------------------- administradores

    public ValidateTemplateResponse validar(Usuario usuario, String fuente) {
        administrador(usuario);
        Analisis analisis = Lenguaje.analizar(fuente);
        return ValidateTemplateResponse.newBuilder().addAllDiagnostics(analisis.diagnosticos())
                .addAllVariables(analisis.variables()).build();
    }

    public PreviewTemplateResponse previsualizar(Usuario usuario, String fuente, Map<String, TemplateValue> valores) {
        administrador(usuario);
        Analisis analisis = Lenguaje.analizar(fuente);
        try {
            return PreviewTemplateResponse.newBuilder().setHtml(Documento.html(analisis.nodos(), valores))
                    .addAllDiagnostics(analisis.diagnosticos()).build();
        } catch (Documento.DemasiadoGrande e) {
            throw new FalloRpc(Tipo.ARGUMENTO_INVALIDO, "La vista previa es demasiado grande: usa listas más cortas");
        }
    }

    /**
     * Guarda un borrador. Si la version mas nueva ya es un borrador, lo reemplaza; si no, agrega uno
     * nuevo. Una fuente con errores no se guarda.
     */
    public SaveTemplateDraftResponse guardarBorrador(Usuario usuario, SaveTemplateDraftRequest peticion) {
        administrador(usuario);
        Analisis analisis = Lenguaje.analizar(peticion.getSource());
        if (analisis.tieneErrores()) {
            TemplateDiagnostic primero = analisis.primerError();
            throw new FalloRpc(Tipo.ARGUMENTO_INVALIDO, "La plantilla tiene errores: " + primero.getMessage()
                    + " (línea " + primero.getLine() + ", columna " + primero.getColumn() + ")",
                    ERROR_DE_SINTAXIS, TemplateDiagnostics.newBuilder()
                            .addAllDiagnostics(analisis.diagnosticos()).build());
        }
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> {
            UUID id;
            if (peticion.getTemplateId().isEmpty()) {
                id = UUID.randomUUID();
                int creadas = Bd.ejecutar(c, "insert into plantilla (id, slug, organization_id, created_by, created_at)"
                        + " values (?, ?, ?, ?, ?) on conflict (slug) do nothing", id, peticion.getSlug(),
                        usuario.organizacionId(), usuario.cuentaId(), ahora);
                if (creadas == 0) {
                    throw new FalloRpc(Tipo.YA_EXISTE, "Ya existe una plantilla con esa dirección (slug)");
                }
            } else {
                id = UUID.fromString(peticion.getTemplateId());
                propia(c, usuario, id, true);
            }
            Optional<Ultima> ultima = ultima(c, id);
            if (ultima.isPresent() && ultima.get().estado().equals("DRAFT")) {
                Bd.ejecutar(c, "update plantilla_version set title = ?, description = ?, category = ?, source = ?,"
                        + " updated_by = ?, updated_at = ? where plantilla_id = ? and version = ?",
                        peticion.getTitle(), peticion.getDescription(), texto(peticion.getCategory()),
                        peticion.getSource(), usuario.cuentaId(), ahora, id, ultima.get().version());
            } else {
                Bd.ejecutar(c, "insert into plantilla_version (plantilla_id, version, state, title, description,"
                        + " category, source, updated_by, updated_at) values (?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?)", id,
                        ultima.map(u -> u.version() + 1).orElse(1), peticion.getTitle(), peticion.getDescription(),
                        texto(peticion.getCategory()), peticion.getSource(), usuario.cuentaId(), ahora);
            }
            return SaveTemplateDraftResponse.newBuilder().setTemplate(version(c, id, ultima(c, id).orElseThrow()
                    .version(), true).orElseThrow()).build();
        });
    }

    /** Publica un borrador; la version publicada antes queda reemplazada. */
    public PublishTemplateResponse publicar(Usuario usuario, UUID id, int numero) {
        administrador(usuario);
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> {
            propia(c, usuario, id, true);
            Template borrador = version(c, id, numero, true)
                    .orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "Esa versión no existe"));
            if (borrador.getState() != VersionState.VERSION_STATE_DRAFT) {
                throw new FalloRpc(Tipo.PRECONDICION, "Solo se publica un borrador; esa versión ya fue publicada");
            }
            if (Lenguaje.analizar(borrador.getSource()).tieneErrores()) {
                throw new FalloRpc(Tipo.PRECONDICION, "El borrador tiene errores: corrígelo antes de publicar");
            }
            Bd.ejecutar(c, "update plantilla_version set state = 'SUPERSEDED' where plantilla_id = ?"
                    + " and state = 'PUBLISHED'", id);
            Bd.ejecutar(c, "update plantilla_version set state = 'PUBLISHED', published_at = ? where plantilla_id = ?"
                    + " and version = ?", ahora, id, numero);
            return PublishTemplateResponse.newBuilder().setTemplate(version(c, id, numero, true).orElseThrow())
                    .build();
        });
    }

    /** Todas las versiones, la mas nueva primero, sin la fuente. */
    public ListTemplateVersionsResponse versiones(Usuario usuario, UUID id) {
        administrador(usuario);
        return bd.transaccion(c -> {
            propia(c, usuario, id, false);
            return ListTemplateVersionsResponse.newBuilder().addAllVersions(Bd.varios(c, "select " + COLUMNAS
                    + " from plantilla p join plantilla_version v on v.plantilla_id = p.id where p.id = ?"
                    + " order by v.version desc", f -> plantilla(f, false), id)).build();
        });
    }

    /**
     * Una version con su fuente. Los borradores solo los abren los administradores de la organizacion
     * autora (el editor). Una version ya publicada, del sistema o de la propia organizacion, la lee
     * cualquier persona con sesion: el acta se llena en el navegador con la version que fijo.
     */
    public GetTemplateVersionResponse version(Usuario usuario, UUID id, int numero) {
        return bd.transaccion(c -> {
            record Datos(Optional<UUID> organizacion, String estado) {
            }
            Datos datos = Bd.uno(c, "select p.organization_id, v.state from plantilla p join plantilla_version v"
                    + " on v.plantilla_id = p.id where p.id = ? and v.version = ?",
                    f -> new Datos(Optional.ofNullable(Bd.uuid(f, "organization_id")), f.getString("state")),
                    id, numero).orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "Esa versión no existe"));
            boolean publicada = !"DRAFT".equals(datos.estado());
            boolean alcance = datos.organizacion().map(usuario.organizacionId()::equals).orElse(true);
            if (!publicada || !alcance) {
                administrador(usuario);
                propia(c, usuario, id, false);
            }
            return GetTemplateVersionResponse.newBuilder().setTemplate(version(c, id, numero, true).orElseThrow())
                    .build();
        });
    }

    /** Version mas nueva de cada plantilla de la organizacion (borradores incluidos), sin la fuente. */
    public ListOrganizationTemplatesResponse deMiOrganizacion(Usuario usuario, int tamano, String token) {
        administrador(usuario);
        int desde = Filas.desplazamiento(token);
        return bd.transaccion(c -> {
            List<Template> filas = Bd.varios(c, "select " + COLUMNAS + " from plantilla p join plantilla_version v"
                    + " on v.plantilla_id = p.id and v.version = (select max(x.version) from plantilla_version x"
                    + " where x.plantilla_id = p.id) where p.organization_id = ?"
                    + " order by v.updated_at desc, p.id limit ? offset ?", f -> plantilla(f, false),
                    usuario.organizacionId(), tamano + 1, desde);
            ListOrganizationTemplatesResponse.Builder respuesta = ListOrganizationTemplatesResponse.newBuilder();
            filas.stream().limit(tamano).forEach(respuesta::addTemplates);
            String siguiente = filas.size() > tamano ? Filas.token(desde + tamano) : "";
            return respuesta.setPageInfo(PageInfo.newBuilder().setNextPageToken(siguiente)).build();
        });
    }

    // ----------------------------------------------------------------------------- apoyo

    private static void administrador(Usuario usuario) {
        if (!"ADMIN".equals(usuario.rol())) {
            throw new FalloRpc(Tipo.SIN_PERMISO, "Solo los administradores gestionan plantillas");
        }
    }

    /** La plantilla existe y es de la organizacion de quien llama (bloqueada si se va a cambiar). */
    private static void propia(Connection c, Usuario usuario, UUID id, boolean bloquear) throws SQLException {
        Optional<Optional<UUID>> organizacion = Bd.uno(c, "select organization_id from plantilla where id = ?"
                + (bloquear ? " for update" : ""), f -> Optional.ofNullable(Bd.uuid(f, "organization_id")), id);
        if (organizacion.isEmpty()) {
            throw new FalloRpc(Tipo.NO_ENCONTRADO, "La plantilla no existe");
        }
        if (organizacion.get().isEmpty()) {
            throw new FalloRpc(Tipo.SIN_PERMISO, "Las plantillas del sistema no se editan: crea una propia a partir"
                    + " de ella");
        }
        if (!organizacion.get().get().equals(usuario.organizacionId())) {
            throw new FalloRpc(Tipo.SIN_PERMISO, "Solo la organización autora edita esta plantilla");
        }
    }

    private record Ultima(int version, String estado) {
    }

    private static Optional<Ultima> ultima(Connection c, UUID id) throws SQLException {
        return Bd.uno(c, "select version, state from plantilla_version where plantilla_id = ?"
                + " order by version desc limit 1", f -> new Ultima(f.getInt("version"), f.getString("state")), id);
    }

    private static Optional<Template> version(Connection c, UUID id, int numero, boolean conFuente)
            throws SQLException {
        return Bd.uno(c, "select " + COLUMNAS + " from plantilla p join plantilla_version v on v.plantilla_id = p.id"
                + " where p.id = ? and v.version = ?", f -> plantilla(f, conFuente), id, numero);
    }

    private static Template plantilla(ResultSet f, boolean conFuente) throws SQLException {
        String fuente = f.getString("source");
        Template.Builder t = Template.newBuilder().setId(Bd.uuid(f, "id").toString()).setSlug(f.getString("slug"))
                .setTitle(f.getString("title")).setDescription(f.getString("description"))
                .setCategory(TemplateCategory.valueOf("TEMPLATE_CATEGORY_" + f.getString("category")))
                .setVersion(f.getInt("version")).setState(VersionState.valueOf("VERSION_STATE_" + f.getString("state")))
                .addAllVariables(Lenguaje.analizar(fuente).variables())
                .setUpdatedAt(Filas.marca(Bd.instante(f, "updated_at")));
        Instant publicada = Bd.instante(f, "published_at");
        if (publicada != null) {
            t.setPublishedAt(Filas.marca(publicada));
        }
        if (conFuente) {
            t.setSource(fuente);
        }
        return t.build();
    }

    private static String texto(TemplateCategory categoria) {
        return categoria.name().substring("TEMPLATE_CATEGORY_".length());
    }
}

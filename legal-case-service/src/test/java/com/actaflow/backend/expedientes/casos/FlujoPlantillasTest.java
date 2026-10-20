package com.actaflow.backend.expedientes.casos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.actaflow.backend.expedientes.aplicacion.ServicioPlantillas;
import com.actaflow.backend.expedientes.grpc.ServicioPlantillasGrpc;
import com.actaflow.backend.expedientes.seguridad.ClavesIdentidad;
import com.actaflow.backend.expedientes.seguridad.InterceptorSesion;
import com.actaflow.backend.plataforma.grpc.InterceptorClienteFirma;
import com.actaflow.backend.plataforma.grpc.Metadatos;
import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.backend.plataforma.seguridad.TokenAcceso;
import com.actaflow.contracts.common.v1.ErrorReason;
import com.actaflow.contracts.common.v1.FieldViolations;
import com.actaflow.contracts.common.v1.PageRequest;
import com.actaflow.contracts.identity.v1.VerificationKey;
import com.actaflow.contracts.templates.v1.GetPublishedTemplateRequest;
import com.actaflow.contracts.templates.v1.GetTemplateVersionRequest;
import com.actaflow.contracts.templates.v1.ListOrganizationTemplatesRequest;
import com.actaflow.contracts.templates.v1.ListPublishedTemplatesRequest;
import com.actaflow.contracts.templates.v1.ListPublishedTemplatesResponse;
import com.actaflow.contracts.templates.v1.ListTemplateVersionsRequest;
import com.actaflow.contracts.templates.v1.PreviewTemplateRequest;
import com.actaflow.contracts.templates.v1.PublishTemplateRequest;
import com.actaflow.contracts.templates.v1.SaveTemplateDraftRequest;
import com.actaflow.contracts.templates.v1.Template;
import com.actaflow.contracts.templates.v1.TemplateCategory;
import com.actaflow.contracts.templates.v1.TemplateDiagnostics;
import com.actaflow.contracts.templates.v1.TemplateServiceGrpc;
import com.actaflow.contracts.templates.v1.TemplateValue;
import com.actaflow.contracts.templates.v1.ValidateTemplateRequest;
import com.actaflow.contracts.templates.v1.VersionState;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;

import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.MetadataUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plantillas de punta a punta por gRPC (en memoria), con la firma HMAC del BFF, tokens Ed25519 y
 * PostgreSQL real: biblioteca publica sin sesion, borradores, publicacion y permisos.
 */
class FlujoPlantillasTest {

    private static final String SECRETO_BFF = "secreto-de-prueba-publico-solo-para-tests-0123456789";
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTRA_ORG = UUID.randomUUID();
    private static final String FUENTE = "# Acta {{ caso.radicado }}\n\nMonto: {{ acuerdo.monto | moneda }}";

    private final KeyPair claves = par();
    private final String kid = TokenAcceso.idDeClave(claves.getPublic());
    private final Clock reloj = Clock.systemUTC();
    private ServidorGrpc servidor;
    private Channel canal;

    private record Persona(UUID cuenta, UUID org, String rol) {
    }

    private final Persona admin = new Persona(UUID.randomUUID(), ORG, "ADMIN");
    private final Persona miembro = new Persona(UUID.randomUUID(), ORG, "MEMBER");
    private final Persona adminAjeno = new Persona(UUID.randomUUID(), OTRA_ORG, "ADMIN");

    private static KeyPair par() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void iniciar() {
        Bd bd = new Bd(new BaseDatosExpedientes().recrear());
        VerificationKey clave = VerificationKey.newBuilder().setKeyId(kid).setAlgorithm("Ed25519")
                .setPublicKeySpki(ByteString.copyFrom(claves.getPublic().getEncoded())).build();
        InterceptorSesion sesion = new InterceptorSesion(new ClavesIdentidad(() -> List.of(clave), reloj), reloj,
                ServicioPlantillasGrpc.PUBLICOS);
        FirmaHmac firma = new FirmaHmac(SECRETO_BFF, 300, reloj);
        servidor = new ServidorGrpc(List.of(() -> ServerInterceptors.intercept(
                new ServicioPlantillasGrpc(new ServicioPlantillas(bd, reloj)), sesion)), firma, -1, 1 << 20);
        servidor.iniciar();
        canal = ClientInterceptors.intercept(servidor.canalEnMemoria(), new InterceptorClienteFirma(firma));
    }

    @AfterEach
    void apagar() {
        servidor.close();
    }

    // ------------------------------------------------------------------------ apoyo

    private String token(Persona p) {
        Map<String, Object> carga = new LinkedHashMap<>();
        carga.put("iss", TokenAcceso.EMISOR);
        carga.put("sub", p.cuenta().toString());
        carga.put("org", p.org().toString());
        carga.put("role", p.rol());
        carga.put("email", p.rol().toLowerCase() + "@prueba.test");
        carga.put("name", p.rol());
        carga.put("exp", Instant.now().getEpochSecond() + 600);
        String firmable = TokenAcceso.firmable(kid, carga);
        try {
            Signature firma = Signature.getInstance("Ed25519");
            firma.initSign(claves.getPrivate());
            firma.update(firmable.getBytes(StandardCharsets.US_ASCII));
            return firmable + "." + TokenAcceso.firma(firma.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private TemplateServiceGrpc.TemplateServiceBlockingStub como(Persona p) {
        Metadata cabeceras = new Metadata();
        cabeceras.put(Metadatos.AUTORIZACION, "Bearer " + token(p));
        return TemplateServiceGrpc.newBlockingStub(ClientInterceptors.intercept(canal,
                MetadataUtils.newAttachHeadersInterceptor(cabeceras)));
    }

    private TemplateServiceGrpc.TemplateServiceBlockingStub anonimo() {
        return TemplateServiceGrpc.newBlockingStub(canal);
    }

    private static StatusRuntimeException falla(Runnable llamada) {
        return assertThrows(StatusRuntimeException.class, llamada::run);
    }

    private static Status.Code codigo(Runnable llamada) {
        return falla(llamada).getStatus().getCode();
    }

    private static PageRequest pagina(int tamano) {
        return PageRequest.newBuilder().setPageSize(tamano).build();
    }

    private static SaveTemplateDraftRequest.Builder borrador(String slug, String fuente) {
        return SaveTemplateDraftRequest.newBuilder().setSlug(slug).setTitle("Acta de prueba " + slug)
                .setDescription("Para las pruebas").setCategory(TemplateCategory.TEMPLATE_CATEGORY_CONTRACT)
                .setSource(fuente);
    }

    private ListPublishedTemplatesResponse publicadas() {
        return anonimo().listPublishedTemplates(ListPublishedTemplatesRequest.newBuilder().setPage(pagina(50)).build());
    }

    // ------------------------------------------------------------------------ pruebas

    @Test
    void laBibliotecaPublicadaSeLeeSinSesionYTraeLasPlantillasDelSistema() {
        ListPublishedTemplatesResponse lista = publicadas();
        assertEquals(List.of("acta-conciliacion-arrendamiento", "acta-de-mediacion", "constancia-de-no-acuerdo"),
                lista.getTemplatesList().stream().map(Template::getSlug).toList());
        assertTrue(lista.getTemplatesList().stream().allMatch(t -> t.getSource().isEmpty()
                && t.getVariablesCount() > 0 && t.getState() == VersionState.VERSION_STATE_PUBLISHED));

        Template acta = anonimo().getPublishedTemplate(GetPublishedTemplateRequest.newBuilder()
                .setSlug("acta-conciliacion-arrendamiento").build()).getTemplate();
        assertTrue(acta.getSource().startsWith("# ACTA DE CONCILIACIÓN"));
        assertTrue(acta.hasPublishedAt());

        ListPublishedTemplatesResponse filtrada = anonimo().listPublishedTemplates(ListPublishedTemplatesRequest
                .newBuilder().setPage(pagina(50)).setCategory(TemplateCategory.TEMPLATE_CATEGORY_MEDIATION_MINUTE)
                .build());
        assertEquals(1, filtrada.getTemplatesCount());

        ListPublishedTemplatesResponse primera = anonimo().listPublishedTemplates(ListPublishedTemplatesRequest
                .newBuilder().setPage(pagina(2)).build());
        assertEquals(2, primera.getTemplatesCount());
        assertFalse(primera.getPageInfo().getNextPageToken().isEmpty());

        assertEquals(Status.Code.NOT_FOUND, codigo(() -> anonimo().getPublishedTemplate(
                GetPublishedTemplateRequest.newBuilder().setSlug("no-existe").build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, codigo(() -> anonimo().getPublishedTemplate(
                GetPublishedTemplateRequest.newBuilder().setSlug("Con Espacios").build())));
    }

    @Test
    void loDemasExigeSesionYRolDeAdministrador() {
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> anonimo().validateTemplate(
                ValidateTemplateRequest.newBuilder().setSource("x").build())));
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> anonimo().saveTemplateDraft(
                borrador("anonima", FUENTE).build())));
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> como(miembro).saveTemplateDraft(
                borrador("del-miembro", FUENTE).build())));
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> como(miembro).listOrganizationTemplates(
                ListOrganizationTemplatesRequest.newBuilder().setPage(pagina(10)).build())));
    }

    @Test
    void validaYPrevisualizaConDiagnosticosYHtmlSeguro() {
        var validacion = como(admin).validateTemplate(ValidateTemplateRequest.newBuilder()
                .setSource("{% si acuerdo.total %}\n{{ acuerdo.total | moneda }}").build());
        assertEquals(1, validacion.getDiagnosticsCount());
        assertEquals(1, validacion.getDiagnostics(0).getLine());
        assertEquals("acuerdo.total", validacion.getVariables(0).getPath());

        var vista = como(admin).previewTemplate(PreviewTemplateRequest.newBuilder().setSource(FUENTE)
                .putValues("caso.radicado", TemplateValue.newBuilder().setText("<b>CC-1</b>").build()).build());
        assertEquals("<h2>Acta <span class=\"valor\">&lt;b&gt;CC-1&lt;/b&gt;</span></h2>\n"
                + "<p>Monto: <mark class=\"falta\">[acuerdo.monto]</mark></p>\n", vista.getHtml());

        StatusRuntimeException sinTipo = falla(() -> como(admin).previewTemplate(PreviewTemplateRequest.newBuilder()
                .setSource(FUENTE).putValues("caso.radicado", TemplateValue.getDefaultInstance()).build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, sinTipo.getStatus().getCode());
        assertEquals("values[\"caso.radicado\"].kind", detalle(sinTipo, FieldViolations.class).getViolations(0)
                .getField());
    }

    @Test
    void unaFuenteConErroresNoSeGuardaYDevuelveLosDiagnosticos() {
        StatusRuntimeException error = falla(() -> como(admin).saveTemplateDraft(
                borrador("con-errores", "Hola\n{% si acuerdo.total %}\nTexto").build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
        assertTrue(error.getStatus().getDescription().contains("línea 2"));
        assertEquals(ServicioPlantillas.ERROR_DE_SINTAXIS, detalle(error, ErrorReason.class).getReason());
        assertEquals(2, detalle(error, TemplateDiagnostics.class).getDiagnostics(0).getLine());
        assertEquals(0, como(admin).listOrganizationTemplates(ListOrganizationTemplatesRequest.newBuilder()
                .setPage(pagina(10)).build()).getTemplatesCount());
    }

    @Test
    void borradorPublicacionYNuevaVersion() {
        Template v1 = como(admin).saveTemplateDraft(borrador("acta-propia", FUENTE).build()).getTemplate();
        assertEquals(1, v1.getVersion());
        assertEquals(VersionState.VERSION_STATE_DRAFT, v1.getState());
        assertFalse(v1.hasPublishedAt());

        // El borrador no esta en la biblioteca, pero el administrador lo encuentra.
        assertEquals(3, publicadas().getTemplatesCount());
        assertEquals(List.of(v1.getId()), como(admin).listOrganizationTemplates(ListOrganizationTemplatesRequest
                .newBuilder().setPage(pagina(10)).build()).getTemplatesList().stream().map(Template::getId).toList());

        // El editor vuelve a abrir el borrador con su fuente.
        assertEquals(FUENTE, como(admin).getTemplateVersion(GetTemplateVersionRequest.newBuilder()
                .setTemplateId(v1.getId()).setVersion(1).build()).getTemplate().getSource());

        // Guardar otra vez reemplaza el mismo borrador.
        Template otraVez = como(admin).saveTemplateDraft(borrador("acta-propia", FUENTE + "\n\nFin.")
                .setTemplateId(v1.getId()).build()).getTemplate();
        assertEquals(1, otraVez.getVersion());
        assertTrue(otraVez.getSource().endsWith("Fin."));

        Template publicada = como(admin).publishTemplate(PublishTemplateRequest.newBuilder()
                .setTemplateId(v1.getId()).setVersion(1).build()).getTemplate();
        assertEquals(VersionState.VERSION_STATE_PUBLISHED, publicada.getState());
        assertTrue(publicada.hasPublishedAt());
        assertEquals(4, publicadas().getTemplatesCount());
        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> como(admin).publishTemplate(
                PublishTemplateRequest.newBuilder().setTemplateId(v1.getId()).setVersion(1).build())));

        // Guardar despues de publicar abre la version 2; publicarla reemplaza a la 1.
        Template v2 = como(admin).saveTemplateDraft(borrador("acta-propia", FUENTE + "\n\nVersion dos.")
                .setTemplateId(v1.getId()).build()).getTemplate();
        assertEquals(2, v2.getVersion());
        assertTrue(anonimo().getPublishedTemplate(GetPublishedTemplateRequest.newBuilder().setSlug("acta-propia")
                .build()).getTemplate().getSource().endsWith("Fin."));
        como(admin).publishTemplate(PublishTemplateRequest.newBuilder().setTemplateId(v1.getId()).setVersion(2)
                .build());
        assertTrue(anonimo().getPublishedTemplate(GetPublishedTemplateRequest.newBuilder().setSlug("acta-propia")
                .build()).getTemplate().getSource().endsWith("Version dos."));
        assertEquals(List.of(VersionState.VERSION_STATE_PUBLISHED, VersionState.VERSION_STATE_SUPERSEDED),
                como(admin).listTemplateVersions(ListTemplateVersionsRequest.newBuilder().setTemplateId(v1.getId())
                        .build()).getVersionsList().stream().map(Template::getState).toList());
        assertEquals(4, publicadas().getTemplatesCount());

        assertEquals(Status.Code.NOT_FOUND, codigo(() -> como(admin).publishTemplate(PublishTemplateRequest
                .newBuilder().setTemplateId(v1.getId()).setVersion(9).build())));
    }

    @Test
    void soloLaOrganizacionAutoraEditaYElSlugEsUnico() {
        Template mia = como(admin).saveTemplateDraft(borrador("de-mi-centro", FUENTE).build()).getTemplate();
        assertEquals(Status.Code.ALREADY_EXISTS, codigo(() -> como(adminAjeno).saveTemplateDraft(
                borrador("de-mi-centro", FUENTE).build())));
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> como(adminAjeno).saveTemplateDraft(
                borrador("de-mi-centro", FUENTE).setTemplateId(mia.getId()).build())));
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> como(adminAjeno).publishTemplate(
                PublishTemplateRequest.newBuilder().setTemplateId(mia.getId()).setVersion(1).build())));
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> como(adminAjeno).getTemplateVersion(
                GetTemplateVersionRequest.newBuilder().setTemplateId(mia.getId()).setVersion(1).build())));
        assertEquals(Status.Code.NOT_FOUND, codigo(() -> como(admin).getTemplateVersion(
                GetTemplateVersionRequest.newBuilder().setTemplateId(mia.getId()).setVersion(2).build())));
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> como(adminAjeno).listTemplateVersions(
                ListTemplateVersionsRequest.newBuilder().setTemplateId(mia.getId()).build())));
        assertEquals(0, como(adminAjeno).listOrganizationTemplates(ListOrganizationTemplatesRequest.newBuilder()
                .setPage(pagina(10)).build()).getTemplatesCount());

        String sistema = publicadas().getTemplates(0).getId();
        StatusRuntimeException ajena = falla(() -> como(admin).saveTemplateDraft(
                borrador("acta-conciliacion-arrendamiento", FUENTE).setTemplateId(sistema).build()));
        assertEquals(Status.Code.PERMISSION_DENIED, ajena.getStatus().getCode());
        assertTrue(ajena.getStatus().getDescription().contains("sistema"));
        assertEquals(Status.Code.NOT_FOUND, codigo(() -> como(admin).listTemplateVersions(
                ListTemplateVersionsRequest.newBuilder().setTemplateId(UUID.randomUUID().toString()).build())));
    }

    @Test
    void lasReglasDelContratoSeAplicanAntesDelCasoDeUso() {
        StatusRuntimeException error = falla(() -> como(admin).saveTemplateDraft(SaveTemplateDraftRequest
                .newBuilder().setTemplateId("no-es-uuid").setSlug("Mal Slug").setTitle("x").build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
        assertEquals(List.of("template_id", "slug", "title", "category", "source"),
                detalle(error, FieldViolations.class).getViolationsList().stream().map(v -> v.getField()).toList());
        assertEquals(Status.Code.INVALID_ARGUMENT, codigo(() -> como(admin).publishTemplate(
                PublishTemplateRequest.newBuilder().setTemplateId(UUID.randomUUID().toString()).setVersion(0)
                        .build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, codigo(() -> anonimo().listPublishedTemplates(
                ListPublishedTemplatesRequest.getDefaultInstance())));
    }

    private static <T extends com.google.protobuf.Message> T detalle(StatusRuntimeException e, Class<T> tipo) {
        com.google.rpc.Status estado = StatusProto.fromThrowable(e);
        for (Any any : estado.getDetailsList()) {
            if (any.is(tipo)) {
                try {
                    return any.unpack(tipo);
                } catch (com.google.protobuf.InvalidProtocolBufferException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
        throw new AssertionError("Sin detalle " + tipo.getSimpleName());
    }
}

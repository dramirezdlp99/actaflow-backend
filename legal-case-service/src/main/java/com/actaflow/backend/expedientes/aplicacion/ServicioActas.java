package com.actaflow.backend.expedientes.aplicacion;

import java.security.PublicKey;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.actaflow.backend.expedientes.aplicacion.Filas.Fila;
import com.actaflow.backend.expedientes.sello.Sellador;
import com.actaflow.backend.expedientes.seguridad.ClavesIdentidad;
import com.actaflow.backend.expedientes.seguridad.Usuario;
import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.backend.plataforma.rpc.FalloRpc;
import com.actaflow.backend.plataforma.rpc.FalloRpc.Tipo;
import com.actaflow.backend.plataforma.seguridad.TokenAcceso;
import com.actaflow.contracts.cases.v1.CaseEventKind;
import com.actaflow.contracts.cases.v1.CaseRole;
import com.actaflow.contracts.crypto.v1.SealedEnvelope;
import com.actaflow.contracts.minutes.v1.Acceptance;
import com.actaflow.contracts.minutes.v1.AcceptFinalDocumentRequest;
import com.actaflow.contracts.minutes.v1.AcceptFinalDocumentResponse;
import com.actaflow.contracts.minutes.v1.CloseMinuteRequest;
import com.actaflow.contracts.minutes.v1.CloseMinuteResponse;
import com.actaflow.contracts.minutes.v1.CreateMinuteRequest;
import com.actaflow.contracts.minutes.v1.CreateMinuteResponse;
import com.actaflow.contracts.minutes.v1.DocumentSeal;
import com.actaflow.contracts.minutes.v1.FinalDocument;
import com.actaflow.contracts.minutes.v1.GetFinalDocumentResponse;
import com.actaflow.contracts.minutes.v1.GetMinuteResponse;
import com.actaflow.contracts.minutes.v1.ListMinutesResponse;
import com.actaflow.contracts.minutes.v1.Minute;
import com.actaflow.contracts.minutes.v1.MinuteState;
import com.actaflow.contracts.minutes.v1.VerifyDocumentResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Casos de uso de las actas (MinuteService). El acta se llena en el navegador (ADR 0004): aqui se
 * fija la plantilla, se guarda el contenido final SELLADO para el motor, se sella el PDF que el
 * motor devuelve (sin verlo) y se registran las aceptaciones con la prueba de presencia de identity.
 */
public final class ServicioActas {

    static final String RENDERIZAR = "RENDERIZAR_ACTA";
    /** Proposito de la prueba de presencia: "accept-minute:<documento>:<sha256>". */
    public static final String PROPOSITO_ACEPTAR = "accept-minute:";

    private final Bd bd;
    private final Clock reloj;
    private final ClavesIdentidad claves;

    public ServicioActas(Bd bd, Clock reloj, ClavesIdentidad claves) {
        this.bd = bd;
        this.reloj = reloj;
        this.claves = claves;
    }

    // ------------------------------------------------------------------------------ acta

    public CreateMinuteResponse crear(Usuario usuario, CreateMinuteRequest p) {
        UUID casoId = UUID.fromString(p.getCaseId());
        UUID plantillaId = UUID.fromString(p.getTemplateId());
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> Idempotencia.una(c, usuario, UUID.fromString(p.getIdempotencyKey()),
                "CreateMinute", CreateMinuteResponse.parser(), ahora, () -> {
                    Fila yo = ServicioCasos.miembro(c, casoId, usuario, true);
                    ServicioCasos.exigirConciliador(yo);
                    ServicioCasos.exigirAbierto(Filas.casoPorId(c, casoId));
                    record Version(Optional<UUID> organizacion, String estado) {
                    }
                    Version version = Bd.uno(c, "select p.organization_id, v.state from plantilla p"
                            + " join plantilla_version v on v.plantilla_id = p.id where p.id = ? and v.version = ?",
                            f -> new Version(Optional.ofNullable(Bd.uuid(f, "organization_id")), f.getString("state")),
                            plantillaId, p.getTemplateVersion())
                            .filter(v -> v.organizacion().map(usuario.organizacionId()::equals).orElse(true))
                            .orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "La plantilla no existe"));
                    if ("DRAFT".equals(version.estado())) {
                        throw new FalloRpc(Tipo.PRECONDICION, "Solo se usan versiones publicadas de una plantilla");
                    }
                    UUID id = UUID.randomUUID();
                    Bd.ejecutar(c, "insert into acta (id, caso_id, plantilla_id, plantilla_version, state, created_by,"
                            + " created_at) values (?, ?, ?, ?, 'DRAFT', ?, ?)", id, casoId, plantillaId,
                            p.getTemplateVersion(), yo.id(), ahora);
                    ServicioCasos.evento(c, casoId, CaseEventKind.CASE_EVENT_KIND_MINUTE_CREATED, yo.id(),
                            yo.nombre(), ahora);
                    return CreateMinuteResponse.newBuilder().setMinute(acta(c, id)).build();
                }));
    }

    public GetMinuteResponse obtener(Usuario usuario, UUID actaId) {
        return bd.transaccion(c -> {
            ServicioCasos.miembro(c, casoDe(c, actaId), usuario, false);
            return GetMinuteResponse.newBuilder().setMinute(acta(c, actaId)).build();
        });
    }

    public ListMinutesResponse listar(Usuario usuario, UUID casoId) {
        return bd.transaccion(c -> {
            ServicioCasos.miembro(c, casoId, usuario, false);
            ListMinutesResponse.Builder respuesta = ListMinutesResponse.newBuilder();
            for (UUID id : Bd.varios(c, "select id from acta where caso_id = ? order by created_at desc, id",
                    f -> Bd.uuid(f, "id"), casoId)) {
                respuesta.addMinutes(acta(c, id));
            }
            return respuesta.build();
        });
    }

    /** Cierra el acta: guarda el contenido final sellado para el motor y encola el PDF y el sello. */
    public CloseMinuteResponse cerrar(Usuario usuario, CloseMinuteRequest p) {
        UUID actaId = UUID.fromString(p.getMinuteId());
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> Idempotencia.una(c, usuario, UUID.fromString(p.getIdempotencyKey()),
                "CloseMinute", CloseMinuteResponse.parser(), ahora, () -> {
                    UUID casoId = casoDe(c, actaId);
                    Fila yo = ServicioCasos.miembro(c, casoId, usuario, true);
                    ServicioCasos.exigirConciliador(yo);
                    String estado = Bd.uno(c, "select state from acta where id = ? for update",
                            f -> f.getString("state"), actaId).orElseThrow();
                    if (!"DRAFT".equals(estado)) {
                        throw new FalloRpc(Tipo.PRECONDICION, "El acta ya se cerró");
                    }
                    Bd.ejecutar(c, "update acta set state = 'SEALING', closed_at = ?, contenido_final = ?,"
                            + " ultimo_error = null where id = ?", ahora, p.getFinalContent().toByteArray(), actaId);
                    ColaTrabajos.encolar(c, RENDERIZAR, actaId, ahora);
                    String trabajo = Bd.uno(c, "select id from trabajo where tipo = ? and clave_idempotencia = ?",
                            f -> Bd.uuid(f, "id").toString(), RENDERIZAR, actaId.toString()).orElse("");
                    return CloseMinuteResponse.newBuilder().setMinute(acta(c, actaId)).setJobId(trabajo).build();
                }));
    }

    // ------------------------------------------------------------------------------ documento final

    public GetFinalDocumentResponse documento(Usuario usuario, UUID actaId) {
        return bd.transaccion(c -> {
            ServicioCasos.miembro(c, casoDe(c, actaId), usuario, false);
            UUID documentoId = Bd.uno(c, "select id from documento_final where acta_id = ?",
                    f -> Bd.uuid(f, "id"), actaId)
                    .orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "El acta aún no tiene documento final"));
            return GetFinalDocumentResponse.newBuilder().setDocument(documentoFinal(c, documentoId)).build();
        });
    }

    /**
     * Una parte acepta el PDF que leyo. El TOTP lo comprobo identity (ConfirmPresence): aqui se exige
     * su prueba firmada, para esta cuenta y este documento exacto.
     */
    public AcceptFinalDocumentResponse aceptar(Usuario usuario, AcceptFinalDocumentRequest p) {
        UUID documentoId = UUID.fromString(p.getDocumentId());
        Instant ahora = reloj.instant();
        exigirPresencia(usuario, p.getPresenceToken(), PROPOSITO_ACEPTAR + documentoId + ":" + p.getSha256Hex(),
                ahora);
        return bd.transaccion(c -> {
            record Documento(UUID actaId, UUID casoId, String sha) {
            }
            Documento doc = Bd.uno(c, "select d.acta_id, a.caso_id, d.sha256 from documento_final d"
                    + " join acta a on a.id = d.acta_id where d.id = ? for update of a",
                    f -> new Documento(Bd.uuid(f, "acta_id"), Bd.uuid(f, "caso_id"), f.getString("sha256")),
                    documentoId).orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "Documento no encontrado"));
            Fila yo = ServicioCasos.miembro(c, doc.casoId(), usuario, false);
            if (!doc.sha().equals(p.getSha256Hex())) {
                throw new FalloRpc(Tipo.PRECONDICION, "El PDF que leíste no es el que se selló");
            }
            Optional<Optional<Instant>> fila = Bd.uno(c, "select accepted_at from aceptacion where documento_id = ?"
                    + " and participante_id = ?", f -> Optional.ofNullable(Bd.instante(f, "accepted_at")),
                    documentoId, yo.id());
            if (fila.isEmpty()) {
                throw new FalloRpc(Tipo.SIN_PERMISO, "Solo las partes del expediente aceptan el acta");
            }
            if (fila.get().isEmpty()) {
                Bd.ejecutar(c, "update aceptacion set accepted_at = ?, nombre = ? where documento_id = ?"
                        + " and participante_id = ?", ahora, yo.nombre(), documentoId, yo.id());
                ServicioCasos.evento(c, doc.casoId(), CaseEventKind.CASE_EVENT_KIND_DOCUMENT_ACCEPTED, yo.id(),
                        yo.nombre(), ahora);
                boolean faltan = Bd.uno(c, "select 1 as uno from aceptacion where documento_id = ?"
                        + " and accepted_at is null limit 1", f -> true, documentoId).isPresent();
                if (!faltan) {
                    Bd.ejecutar(c, "update acta set state = 'FINAL' where id = ?", doc.actaId());
                }
            }
            return AcceptFinalDocumentResponse.newBuilder().setDocument(documentoFinal(c, documentoId)).build();
        });
    }

    /** Publico: cualquiera con el PDF calcula su SHA-256 y comprueba si ActaFlow lo sello. */
    public VerifyDocumentResponse verificar(String sha256Hex) {
        return bd.transaccion(c -> Bd.uno(c, "select d.id, c.case_number from documento_final d"
                + " join acta a on a.id = d.acta_id join caso c on c.id = a.caso_id where d.sha256 = ?",
                f -> new Object[] {Bd.uuid(f, "id"), f.getString("case_number")}, sha256Hex)
                .map(fila -> {
                    try {
                        UUID id = (UUID) fila[0];
                        int aceptaciones = Bd.uno(c, "select count(accepted_at) as n from aceptacion"
                                + " where documento_id = ?", f -> f.getInt("n"), id).orElse(0);
                        return VerifyDocumentResponse.newBuilder().setAuthentic(true).setSeal(sello(c, id))
                                .setCaseNumber((String) fila[1]).setAcceptanceCount(aceptaciones).build();
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .orElse(VerifyDocumentResponse.newBuilder().setAuthentic(false).build()));
    }

    // ------------------------------------------------------------------------------ cola

    /** Contenido final sellado de un acta que se esta sellando; vacio si ya no hace falta. */
    static Optional<SealedEnvelope> pendiente(Connection c, UUID actaId) throws SQLException {
        Optional<byte[]> datos = Bd.uno(c, "select contenido_final from acta where id = ? and state = 'SEALING'",
                f -> f.getBytes("contenido_final"), actaId);
        if (datos.isEmpty() || datos.get() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(SealedEnvelope.parseFrom(datos.get()));
        } catch (InvalidProtocolBufferException e) {
            return Optional.empty();
        }
    }

    /** Guarda el PDF cifrado y el sello, y pide la aceptacion de cada parte del expediente. */
    static void guardarSellado(Connection c, UUID actaId, SealedEnvelope contenido, MotorIA.Renderizado pdf,
                               Sellador.Sello sello, Instant ahora) throws SQLException {
        UUID casoId = casoDe(c, actaId);
        UUID documentoId = UUID.randomUUID();
        Bd.ejecutar(c, "insert into documento_final (id, acta_id, pdf, clave_para_caso, sha256, cms, firmante,"
                + " huella_certificado, paginas, sealed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", documentoId, actaId,
                pdf.pdf().toByteArray(), contenido.getContentKeyForCase().toByteArray(), pdf.sha256Hex(), sello.cms(),
                sello.firmante(), sello.huellaCertificado(), pdf.paginas(), ahora);
        List<Fila> partes = Filas.participantes(c, casoId).stream()
                .filter(f -> f.rol() == CaseRole.CASE_ROLE_PARTY).toList();
        for (Fila parte : partes) {
            Bd.ejecutar(c, "insert into aceptacion (documento_id, participante_id, nombre) values (?, ?, ?)",
                    documentoId, parte.id(), parte.nombre());
        }
        Bd.ejecutar(c, "update acta set state = ?, contenido_final = null where id = ?",
                partes.isEmpty() ? "FINAL" : "AWAITING_ACCEPTANCE", actaId);
        ServicioCasos.evento(c, casoId, CaseEventKind.CASE_EVENT_KIND_DOCUMENT_SEALED, null, "ActaFlow", ahora);
    }

    /** El motor no pudo generar el PDF: el acta vuelve a borrador para cerrarla otra vez. */
    static void devolverABorrador(Connection c, UUID actaId, String error) throws SQLException {
        Bd.ejecutar(c, "update acta set state = 'DRAFT', closed_at = null, contenido_final = null,"
                + " ultimo_error = ? where id = ? and state = 'SEALING'",
                error.substring(0, Math.min(error.length(), 300)), actaId);
    }

    // ------------------------------------------------------------------------------ apoyo

    private void exigirPresencia(Usuario usuario, String token, String proposito, Instant ahora) {
        Optional<TokenAcceso.Presencia> presencia = TokenAcceso.idClaveDe(token)
                .flatMap(claves::clave)
                .flatMap((PublicKey clave) -> TokenAcceso.verificarPresencia(token, ahora, clave,
                        TokenAcceso.idDeClave(clave)));
        if (presencia.isEmpty() || !presencia.get().cuenta().equals(usuario.cuentaId())
                || !presencia.get().proposito().equals(proposito)) {
            throw new FalloRpc(Tipo.NO_AUTENTICADO, "Confirma otra vez con un código nuevo de tu app autenticadora");
        }
    }

    static UUID casoDe(Connection c, UUID actaId) throws SQLException {
        return Bd.uno(c, "select caso_id from acta where id = ?", f -> Bd.uuid(f, "caso_id"), actaId)
                .orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "Acta no encontrada"));
    }

    private static Minute acta(Connection c, UUID id) throws SQLException {
        return Bd.uno(c, "select id, caso_id, plantilla_id, plantilla_version, state, created_at, closed_at"
                + " from acta where id = ?", ServicioActas::acta, id).orElseThrow();
    }

    private static Minute acta(ResultSet f) throws SQLException {
        Minute.Builder m = Minute.newBuilder()
                .setId(Bd.uuid(f, "id").toString())
                .setCaseId(Bd.uuid(f, "caso_id").toString())
                .setTemplateId(Bd.uuid(f, "plantilla_id").toString())
                .setTemplateVersion(f.getInt("plantilla_version"))
                .setState(MinuteState.valueOf("MINUTE_STATE_" + f.getString("state")))
                .setCreatedAt(Filas.marca(Bd.instante(f, "created_at")));
        Instant cerrada = Bd.instante(f, "closed_at");
        if (cerrada != null) {
            m.setClosedAt(Filas.marca(cerrada));
        }
        return m.build();
    }

    private static DocumentSeal sello(Connection c, UUID documentoId) throws SQLException {
        return Bd.uno(c, "select sha256, cms, firmante, huella_certificado, sealed_at from documento_final"
                + " where id = ?", f -> DocumentSeal.newBuilder()
                .setSha256Hex(f.getString("sha256"))
                .setCmsSignature(ByteString.copyFrom(f.getBytes("cms")))
                .setSignerSubject(f.getString("firmante"))
                .setCertificateFingerprintSha256(f.getString("huella_certificado"))
                .setSealedAt(Filas.marca(Bd.instante(f, "sealed_at")))
                .build(), documentoId).orElseThrow();
    }

    private static FinalDocument documentoFinal(Connection c, UUID documentoId) throws SQLException {
        FinalDocument.Builder d = Bd.uno(c, "select id, acta_id, pdf, clave_para_caso from documento_final"
                + " where id = ?", f -> FinalDocument.newBuilder()
                .setId(Bd.uuid(f, "id").toString())
                .setMinuteId(Bd.uuid(f, "acta_id").toString())
                .setPdf(ServicioAudiencias.blob(f.getBytes("pdf")))
                .setContentKeyForCase(ServicioAudiencias.blob(f.getBytes("clave_para_caso"))), documentoId)
                .orElseThrow();
        d.setSeal(sello(c, documentoId));
        record Fila2(UUID participante, String nombre, Instant cuando) {
        }
        for (Fila2 a : Bd.varios(c, "select participante_id, nombre, accepted_at from aceptacion"
                + " where documento_id = ? order by accepted_at nulls last, nombre", f -> new Fila2(
                Bd.uuid(f, "participante_id"), f.getString("nombre"), Bd.instante(f, "accepted_at")), documentoId)) {
            if (a.cuando() == null) {
                d.addPendingParticipantIds(a.participante().toString());
            } else {
                d.addAcceptances(Acceptance.newBuilder().setParticipantId(a.participante().toString())
                        .setDisplayName(a.nombre()).setAcceptedAt(Filas.marca(a.cuando())));
            }
        }
        return d.build();
    }
}

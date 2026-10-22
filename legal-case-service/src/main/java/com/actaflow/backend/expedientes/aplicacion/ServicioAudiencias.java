package com.actaflow.backend.expedientes.aplicacion;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.actaflow.backend.expedientes.aplicacion.Filas.Fila;
import com.actaflow.backend.expedientes.seguridad.Usuario;
import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.backend.plataforma.rpc.FalloRpc;
import com.actaflow.backend.plataforma.rpc.FalloRpc.Tipo;
import com.actaflow.contracts.cases.v1.CaseEventKind;
import com.actaflow.contracts.cases.v1.CaseRole;
import com.actaflow.contracts.cases.v1.CaseStatus;
import com.actaflow.contracts.cases.v1.LegalCase;
import com.actaflow.contracts.crypto.v1.EncryptedBlob;
import com.actaflow.contracts.hearings.v1.AudioSegment;
import com.actaflow.contracts.hearings.v1.ConfirmEntitiesRequest;
import com.actaflow.contracts.hearings.v1.ConfirmEntitiesResponse;
import com.actaflow.contracts.hearings.v1.EndHearingResponse;
import com.actaflow.contracts.hearings.v1.EntityExtraction;
import com.actaflow.contracts.hearings.v1.GetEntityExtractionResponse;
import com.actaflow.contracts.hearings.v1.GetHearingResponse;
import com.actaflow.contracts.hearings.v1.Hearing;
import com.actaflow.contracts.hearings.v1.HearingState;
import com.actaflow.contracts.hearings.v1.ListHearingsResponse;
import com.actaflow.contracts.hearings.v1.ListSegmentsResponse;
import com.actaflow.contracts.hearings.v1.ProcessingState;
import com.actaflow.contracts.hearings.v1.RequestEntityExtractionRequest;
import com.actaflow.contracts.hearings.v1.RequestEntityExtractionResponse;
import com.actaflow.contracts.hearings.v1.StartHearingResponse;
import com.actaflow.contracts.hearings.v1.SubmitAudioSegmentRequest;
import com.actaflow.contracts.hearings.v1.SubmitAudioSegmentResponse;
import com.actaflow.contracts.hearings.v1.WatchHearingEventsResponse;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Audiencias en vivo. Mismas reglas de acceso que los expedientes: quien no participa del caso
 * recibe NOT_FOUND. Iniciar y terminar es de los conciliadores; pedir y confirmar la extraccion,
 * de conciliadores y asistentes; cualquier participante puede enviar el audio de su intervencion.
 *
 * <p>El trabajo pesado (transcribir, extraer) va a la cola: la API nunca espera a la IA.
 */
public final class ServicioAudiencias {

    static final String TRANSCRIBIR = "TRANSCRIBIR";
    static final String EXTRAER = "EXTRAER";
    private static final int MAX_SEGMENTOS = 500;

    private final Bd bd;
    private final Clock reloj;

    public ServicioAudiencias(Bd bd, Clock reloj) {
        this.bd = bd;
        this.reloj = reloj;
    }

    // ------------------------------------------------------------------------------ audiencias

    public StartHearingResponse iniciar(Usuario usuario, UUID casoId, UUID idempotencia) {
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> Idempotencia.una(c, usuario, idempotencia, "StartHearing",
                StartHearingResponse.parser(), ahora, () -> {
                    Fila yo = ServicioCasos.miembro(c, casoId, usuario, true);
                    ServicioCasos.exigirConciliador(yo);
                    LegalCase caso = Filas.casoPorId(c, casoId);
                    if (caso.getStatus() != CaseStatus.CASE_STATUS_OPEN
                            && caso.getStatus() != CaseStatus.CASE_STATUS_IN_HEARING) {
                        throw new FalloRpc(Tipo.PRECONDICION,
                                "Solo se inicia una audiencia en un expediente abierto o en audiencia");
                    }
                    if (Bd.uno(c, "select 1 as uno from audiencia where caso_id = ? and state = 'IN_PROGRESS'",
                            f -> true, casoId).isPresent()) {
                        throw new FalloRpc(Tipo.PRECONDICION, "Ya hay una audiencia en curso en este expediente");
                    }
                    UUID id = UUID.randomUUID();
                    Bd.ejecutar(c, "insert into audiencia (id, caso_id, state, started_by, started_at)"
                            + " values (?, ?, 'IN_PROGRESS', ?, ?)", id, casoId, yo.id(), ahora);
                    ServicioCasos.evento(c, casoId, CaseEventKind.CASE_EVENT_KIND_HEARING_STARTED, yo.id(), yo.nombre(),
                            ahora);
                    return StartHearingResponse.newBuilder().setHearing(audiencia(c, id)).build();
                }));
    }

    public EndHearingResponse terminar(Usuario usuario, UUID audienciaId) {
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> {
            UUID casoId = casoDe(c, audienciaId, true);
            Fila yo = ServicioCasos.miembro(c, casoId, usuario, false);
            ServicioCasos.exigirConciliador(yo);
            int cambiadas = Bd.ejecutar(c, "update audiencia set state = 'ENDED', ended_at = ? where id = ?"
                    + " and state = 'IN_PROGRESS'", ahora, audienciaId);
            if (cambiadas > 0) {
                eventoAudiencia(c, audienciaId, "ENDED", audienciaId, ahora);
                ServicioCasos.evento(c, casoId, CaseEventKind.CASE_EVENT_KIND_HEARING_ENDED, yo.id(), yo.nombre(),
                        ahora);
            }
            return EndHearingResponse.newBuilder().setHearing(audiencia(c, audienciaId)).build();
        });
    }

    public GetHearingResponse obtener(Usuario usuario, UUID audienciaId) {
        return bd.transaccion(c -> {
            ServicioCasos.miembro(c, casoDe(c, audienciaId, false), usuario, false);
            return GetHearingResponse.newBuilder().setHearing(audiencia(c, audienciaId)).build();
        });
    }

    public ListHearingsResponse listar(Usuario usuario, UUID casoId) {
        return bd.transaccion(c -> {
            ServicioCasos.miembro(c, casoId, usuario, false);
            ListHearingsResponse.Builder respuesta = ListHearingsResponse.newBuilder();
            for (UUID id : Bd.varios(c, "select id from audiencia where caso_id = ? order by started_at desc",
                    f -> Bd.uuid(f, "id"), casoId)) {
                respuesta.addHearings(audiencia(c, id));
            }
            return respuesta.build();
        });
    }

    // ------------------------------------------------------------------------------ segmentos

    /** Guarda el audio sellado y encola su transcripcion. Idempotente por (audiencia, orden). */
    public SubmitAudioSegmentResponse enviarSegmento(Usuario usuario, SubmitAudioSegmentRequest p) {
        UUID audienciaId = UUID.fromString(p.getHearingId());
        UUID segmentoId = UUID.fromString(p.getSegmentId());
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> {
            UUID casoId = casoDe(c, audienciaId, true);
            exigirRedactor(ServicioCasos.miembro(c, casoId, usuario, false));
            Optional<UUID> previo = Bd.uno(c, "select id from segmento_audio where audiencia_id = ? and sequence = ?",
                    f -> Bd.uuid(f, "id"), audienciaId, p.getSequence());
            if (previo.isPresent()) {
                if (!previo.get().equals(segmentoId)) {
                    throw new FalloRpc(Tipo.YA_EXISTE, "Ese numero de segmento ya se uso con otro audio");
                }
                return SubmitAudioSegmentResponse.newBuilder().setSegment(segmento(c, segmentoId)).build();
            }
            if (!enCurso(c, audienciaId)) {
                throw new FalloRpc(Tipo.PRECONDICION, "La audiencia ya termino");
            }
            UUID hablante = UUID.fromString(p.getSpeakerParticipantId());
            if (Bd.uno(c, "select 1 as uno from participante where id = ? and caso_id = ?", f -> true, hablante, casoId)
                    .isEmpty()) {
                throw new FalloRpc(Tipo.ARGUMENTO_INVALIDO, "Quien habla no es participante del expediente",
                        List.of(new FalloRpc.Violacion("speaker_participant_id", "hearing.speaker",
                                "speaker must be a participant of the case")));
            }
            if (Bd.uno(c, "select 1 as uno from segmento_audio where id = ?", f -> true, segmentoId).isPresent()) {
                throw new FalloRpc(Tipo.YA_EXISTE, "Ese segmento ya se envio con otro numero");
            }
            Bd.ejecutar(c, "insert into segmento_audio (id, audiencia_id, sequence, speaker_id, duration_ms, state,"
                    + " audio, clave_para_caso, received_at) values (?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?)", segmentoId,
                    audienciaId, p.getSequence(), hablante, p.getDurationMs(), p.getAudio().toByteArray(),
                    p.getAudio().getContentKeyForCase().toByteArray(), ahora);
            ColaTrabajos.encolar(c, TRANSCRIBIR, segmentoId, ahora);
            eventoAudiencia(c, audienciaId, "SEGMENT", segmentoId, ahora);
            return SubmitAudioSegmentResponse.newBuilder().setSegment(segmento(c, segmentoId)).build();
        });
    }

    public ListSegmentsResponse segmentos(Usuario usuario, UUID audienciaId, int despuesDe) {
        return bd.transaccion(c -> {
            ServicioCasos.miembro(c, casoDe(c, audienciaId, false), usuario, false);
            return ListSegmentsResponse.newBuilder().addAllSegments(Bd.varios(c, "select " + COLUMNAS_SEGMENTO
                    + " from segmento_audio where audiencia_id = ? and sequence > ? order by sequence limit ?",
                    ServicioAudiencias::segmento, audienciaId, despuesDe, MAX_SEGMENTOS)).build();
        });
    }

    // ------------------------------------------------------------------------------ extraccion

    public RequestEntityExtractionResponse pedirExtraccion(Usuario usuario, RequestEntityExtractionRequest p) {
        UUID audienciaId = UUID.fromString(p.getHearingId());
        UUID extraccionId = UUID.fromString(p.getExtractionId());
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> {
            UUID casoId = casoDe(c, audienciaId, true);
            exigirRedactor(ServicioCasos.miembro(c, casoId, usuario, false));
            Optional<UUID> previa = Bd.uno(c, "select audiencia_id from extraccion where id = ?",
                    f -> Bd.uuid(f, "audiencia_id"), extraccionId);
            if (previa.isPresent()) {
                if (!previa.get().equals(audienciaId)) {
                    throw new FalloRpc(Tipo.YA_EXISTE, "Ese identificador de extraccion ya se uso");
                }
                return RequestEntityExtractionResponse.newBuilder().setExtraction(extraccion(c, extraccionId)).build();
            }
            Bd.ejecutar(c, "insert into extraccion (id, audiencia_id, state, entrada, clave_para_caso, created_at,"
                    + " updated_at) values (?, ?, 'QUEUED', ?, ?, ?, ?)", extraccionId, audienciaId,
                    p.getInput().toByteArray(), p.getInput().getContentKeyForCase().toByteArray(), ahora, ahora);
            ColaTrabajos.encolar(c, EXTRAER, extraccionId, ahora);
            eventoAudiencia(c, audienciaId, "EXTRACTION", extraccionId, ahora);
            return RequestEntityExtractionResponse.newBuilder().setExtraction(extraccion(c, extraccionId)).build();
        });
    }

    /** La extraccion mas reciente de la audiencia. */
    public GetEntityExtractionResponse ultimaExtraccion(Usuario usuario, UUID audienciaId) {
        return bd.transaccion(c -> {
            ServicioCasos.miembro(c, casoDe(c, audienciaId, false), usuario, false);
            UUID id = Bd.uno(c, "select id from extraccion where audiencia_id = ? order by created_at desc, id limit 1",
                    f -> Bd.uuid(f, "id"), audienciaId)
                    .orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "Esta audiencia aun no tiene extraccion"));
            return GetEntityExtractionResponse.newBuilder().setExtraction(extraccion(c, id)).build();
        });
    }

    /** Guarda lo revisado por una persona, con concurrencia optimista. */
    public ConfirmEntitiesResponse confirmar(Usuario usuario, ConfirmEntitiesRequest p) {
        UUID extraccionId = UUID.fromString(p.getExtractionId());
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> {
            record Actual(UUID audienciaId, String estado, long revision) {
            }
            Actual actual = Bd.uno(c, "select audiencia_id, state, revision from extraccion where id = ? for update",
                    f -> new Actual(Bd.uuid(f, "audiencia_id"), f.getString("state"), f.getLong("revision")),
                    extraccionId).orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "Extraccion no encontrada"));
            Fila yo = ServicioCasos.miembro(c, casoDe(c, actual.audienciaId(), false), usuario, false);
            exigirRedactor(yo);
            if (!"DONE".equals(actual.estado())) {
                throw new FalloRpc(Tipo.PRECONDICION, "La extraccion aun no termina");
            }
            if (actual.revision() != p.getExpectedRevision()) {
                throw new FalloRpc(Tipo.ABORTADO, "Alguien mas guardo la revision antes; recarga y repite");
            }
            Bd.ejecutar(c, "update extraccion set revisada = ?, revision = revision + 1, revisada_por = ?,"
                    + " updated_at = ? where id = ?", p.getReviewed().toByteArray(), yo.id(), ahora, extraccionId);
            eventoAudiencia(c, actual.audienciaId(), "EXTRACTION", extraccionId, ahora);
            return ConfirmEntitiesResponse.newBuilder().setExtraction(extraccion(c, extraccionId)).build();
        });
    }

    // ------------------------------------------------------------------------------ eventos

    /** Comprueba que quien llama participa del caso de la audiencia (antes de abrir el flujo). */
    public void exigirAcceso(Usuario usuario, UUID audienciaId) {
        bd.transaccion(c -> ServicioCasos.miembro(c, casoDe(c, audienciaId, false), usuario, false));
    }

    /** Eventos despues de `secuencia`, ya armados; y si la audiencia termino. */
    public record Lote(List<WatchHearingEventsResponse> eventos, boolean terminada) {
    }

    public Lote eventos(UUID audienciaId, long secuencia, int maximo) {
        return bd.transaccion(c -> {
            record Evento(long secuencia, String tipo, UUID recurso) {
            }
            List<Evento> filas = Bd.varios(c, "select secuencia, tipo, recurso_id from evento_audiencia"
                    + " where audiencia_id = ? and secuencia > ? order by secuencia limit ?",
                    f -> new Evento(f.getLong("secuencia"), f.getString("tipo"), Bd.uuid(f, "recurso_id")),
                    audienciaId, secuencia, maximo);
            List<WatchHearingEventsResponse> eventos = new ArrayList<>();
            for (Evento e : filas) {
                WatchHearingEventsResponse.Builder b = WatchHearingEventsResponse.newBuilder()
                        .setEventSequence(e.secuencia());
                switch (e.tipo()) {
                    case "SEGMENT" -> b.setSegmentUpdated(segmento(c, e.recurso()));
                    case "EXTRACTION" -> b.setExtractionUpdated(extraccion(c, e.recurso()));
                    default -> b.setHearingEnded(audiencia(c, e.recurso()));
                }
                eventos.add(b.build());
            }
            return new Lote(eventos, !enCurso(c, audienciaId));
        });
    }

    // ------------------------------------------------------------------------------ apoyo

    private static void exigirRedactor(Fila yo) {
        if (yo.rol() != CaseRole.CASE_ROLE_CONCILIATOR && yo.rol() != CaseRole.CASE_ROLE_ASSISTANT) {
            throw new FalloRpc(Tipo.SIN_PERMISO, "Solo un conciliador o un asistente del caso puede hacer esto");
        }
    }

    /** Caso de una audiencia; NOT_FOUND si no existe (sin revelar nada mas). */
    static UUID casoDe(Connection c, UUID audienciaId, boolean bloquear) throws SQLException {
        return Bd.uno(c, "select caso_id from audiencia where id = ?" + (bloquear ? " for update" : ""),
                f -> Bd.uuid(f, "caso_id"), audienciaId)
                .orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "Audiencia no encontrada"));
    }

    private static boolean enCurso(Connection c, UUID audienciaId) throws SQLException {
        return Bd.uno(c, "select 1 as uno from audiencia where id = ? and state = 'IN_PROGRESS'", f -> true,
                audienciaId).isPresent();
    }

    static void eventoAudiencia(Connection c, UUID audienciaId, String tipo, UUID recurso, Instant cuando)
            throws SQLException {
        Bd.ejecutar(c, "insert into evento_audiencia (audiencia_id, tipo, recurso_id, creado_en) values (?, ?, ?, ?)",
                audienciaId, tipo, recurso, cuando);
    }

    private static Hearing audiencia(Connection c, UUID id) throws SQLException {
        return Bd.uno(c, "select a.id, a.caso_id, a.state, a.started_at, a.ended_at,"
                + " (select count(*) from segmento_audio s where s.audiencia_id = a.id) as segmentos,"
                + " (select coalesce(sum(s.duration_ms), 0) from segmento_audio s where s.audiencia_id = a.id)"
                + " as duracion from audiencia a where a.id = ?", f -> {
                    Hearing.Builder h = Hearing.newBuilder().setId(Bd.uuid(f, "id").toString())
                            .setCaseId(Bd.uuid(f, "caso_id").toString())
                            .setState(HearingState.valueOf("HEARING_STATE_" + f.getString("state")))
                            .setStartedAt(Filas.marca(Bd.instante(f, "started_at")))
                            .setSegmentCount(f.getInt("segmentos")).setAudioDurationMs(f.getLong("duracion"));
                    Instant fin = Bd.instante(f, "ended_at");
                    if (fin != null) {
                        h.setEndedAt(Filas.marca(fin));
                    }
                    return h.build();
                }, id).orElseThrow();
    }

    private static final String COLUMNAS_SEGMENTO = "id, audiencia_id, sequence, speaker_id, duration_ms, state,"
            + " clave_para_caso, transcripcion, received_at";

    private static AudioSegment segmento(Connection c, UUID id) throws SQLException {
        return Bd.uno(c, "select " + COLUMNAS_SEGMENTO + " from segmento_audio where id = ?",
                ServicioAudiencias::segmento, id).orElseThrow();
    }

    private static AudioSegment segmento(ResultSet f) throws SQLException {
        AudioSegment.Builder s = AudioSegment.newBuilder().setId(Bd.uuid(f, "id").toString())
                .setHearingId(Bd.uuid(f, "audiencia_id").toString()).setSequence(f.getInt("sequence"))
                .setSpeakerParticipantId(Bd.uuid(f, "speaker_id").toString()).setDurationMs(f.getLong("duration_ms"))
                .setState(ProcessingState.valueOf("PROCESSING_STATE_" + f.getString("state")))
                .setContentKeyForCase(blob(f.getBytes("clave_para_caso")))
                .setReceivedAt(Filas.marca(Bd.instante(f, "received_at")));
        byte[] transcripcion = f.getBytes("transcripcion");
        if (transcripcion != null) {
            s.setTranscript(blob(transcripcion));
        }
        return s.build();
    }

    private static EntityExtraction extraccion(Connection c, UUID id) throws SQLException {
        return Bd.uno(c, "select id, audiencia_id, state, clave_para_caso, propuesta, revisada, revision, revisada_por"
                + " from extraccion where id = ?", f -> {
                    EntityExtraction.Builder e = EntityExtraction.newBuilder().setId(Bd.uuid(f, "id").toString())
                            .setHearingId(Bd.uuid(f, "audiencia_id").toString())
                            .setState(ProcessingState.valueOf("PROCESSING_STATE_" + f.getString("state")))
                            .setContentKeyForCase(blob(f.getBytes("clave_para_caso")))
                            .setRevision(f.getLong("revision"));
                    byte[] propuesta = f.getBytes("propuesta");
                    if (propuesta != null) {
                        e.setProposed(blob(propuesta));
                    }
                    byte[] revisada = f.getBytes("revisada");
                    if (revisada != null) {
                        e.setReviewed(blob(revisada));
                    }
                    UUID por = Bd.uuid(f, "revisada_por");
                    if (por != null) {
                        e.setReviewedByParticipantId(por.toString());
                    }
                    return e.build();
                }, id).orElseThrow();
    }

    static EncryptedBlob blob(byte[] bytes) {
        try {
            return EncryptedBlob.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Blob cifrado ilegible en la base", e);
        }
    }
}

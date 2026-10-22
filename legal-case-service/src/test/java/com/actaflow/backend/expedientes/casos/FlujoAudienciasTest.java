package com.actaflow.backend.expedientes.casos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import com.actaflow.backend.expedientes.aplicacion.ColaTrabajos;
import com.actaflow.backend.expedientes.aplicacion.MotorIA;
import com.actaflow.backend.expedientes.aplicacion.ServicioAudiencias;
import com.actaflow.backend.expedientes.aplicacion.ServicioCasos;
import com.actaflow.backend.expedientes.aplicacion.ServicioParticipantes;
import com.actaflow.backend.expedientes.grpc.ServicioAudienciasGrpc;
import com.actaflow.backend.expedientes.grpc.ServicioCasosGrpc;
import com.actaflow.backend.expedientes.grpc.ServicioParticipantesGrpc;
import com.actaflow.backend.expedientes.seguridad.ClavesIdentidad;
import com.actaflow.backend.expedientes.seguridad.InterceptorSesion;
import com.actaflow.backend.plataforma.grpc.InterceptorClienteFirma;
import com.actaflow.backend.plataforma.grpc.Metadatos;
import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.backend.plataforma.seguridad.TokenAcceso;
import com.actaflow.contracts.cases.v1.CaseKind;
import com.actaflow.contracts.cases.v1.CaseRole;
import com.actaflow.contracts.cases.v1.CaseServiceGrpc;
import com.actaflow.contracts.cases.v1.CreateCaseRequest;
import com.actaflow.contracts.cases.v1.InviteParticipantRequest;
import com.actaflow.contracts.cases.v1.LegalCase;
import com.actaflow.contracts.cases.v1.ListCasesRequest;
import com.actaflow.contracts.cases.v1.Participant;
import com.actaflow.contracts.cases.v1.ParticipantServiceGrpc;
import com.actaflow.contracts.cases.v1.PartyCapacity;
import com.actaflow.contracts.cases.v1.PartySide;
import com.actaflow.contracts.common.v1.PageRequest;
import com.actaflow.contracts.crypto.v1.CipherSuite;
import com.actaflow.contracts.crypto.v1.EncryptedBlob;
import com.actaflow.contracts.crypto.v1.SealedEnvelope;
import com.actaflow.contracts.crypto.v1.WrappedKey;
import com.actaflow.contracts.hearings.v1.AudioSegment;
import com.actaflow.contracts.hearings.v1.ConfirmEntitiesRequest;
import com.actaflow.contracts.hearings.v1.EndHearingRequest;
import com.actaflow.contracts.hearings.v1.EntityExtraction;
import com.actaflow.contracts.hearings.v1.GetEntityExtractionRequest;
import com.actaflow.contracts.hearings.v1.GetHearingRequest;
import com.actaflow.contracts.hearings.v1.Hearing;
import com.actaflow.contracts.hearings.v1.HearingServiceGrpc;
import com.actaflow.contracts.hearings.v1.HearingState;
import com.actaflow.contracts.hearings.v1.ListHearingsRequest;
import com.actaflow.contracts.hearings.v1.ListSegmentsRequest;
import com.actaflow.contracts.hearings.v1.ProcessingState;
import com.actaflow.contracts.hearings.v1.RequestEntityExtractionRequest;
import com.actaflow.contracts.hearings.v1.StartHearingRequest;
import com.actaflow.contracts.hearings.v1.SubmitAudioSegmentRequest;
import com.actaflow.contracts.hearings.v1.WatchHearingEventsRequest;
import com.actaflow.contracts.hearings.v1.WatchHearingEventsResponse;
import com.actaflow.contracts.identity.v1.VerificationKey;
import com.google.protobuf.ByteString;

import io.grpc.BindableService;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Audiencias de punta a punta por gRPC (en memoria) con PostgreSQL real y un motor de IA falso:
 * permisos, segmentos idempotentes, la cola (exito, reintento y fallo definitivo), la extraccion con
 * concurrencia optimista y el flujo de eventos en vivo.
 */
class FlujoAudienciasTest {

    private static final String SECRETO_BFF = "secreto-de-prueba-publico-solo-para-tests-0123456789";
    private static final UUID ORG = UUID.randomUUID();
    private static final Duration FLUJO_MAXIMO = Duration.ofSeconds(3);

    private final KeyPair claves = par();
    private final String kid = TokenAcceso.idDeClave(claves.getPublic());
    private final Clock reloj = Clock.systemUTC();
    private final RelojMovil relojCola = new RelojMovil();
    private final MotorFalso motor = new MotorFalso();
    private ScheduledExecutorService planificador;
    private ServidorGrpc servidor;
    private Channel canal;
    private Bd bd;
    private ColaTrabajos cola;

    private record Persona(UUID cuenta, String rol, String correo, String nombre) {
    }

    private final Persona alicia = new Persona(UUID.randomUUID(), "MEMBER", "alicia@prueba.test", "Alicia");
    private final Persona bruno = new Persona(UUID.randomUUID(), "MEMBER", "bruno@prueba.test", "Bruno");
    private final Persona carla = new Persona(UUID.randomUUID(), "EXTERNAL", "carla@prueba.test", "Carla");
    private final Persona dario = new Persona(UUID.randomUUID(), "MEMBER", "dario@prueba.test", "Dario");

    /** Hora del sistema mas un desfase que la prueba adelanta (para vencer las esperas de reintento). */
    private static final class RelojMovil extends Clock {
        private volatile Duration desfase = Duration.ZERO;

        void adelantar(Duration d) {
            desfase = desfase.plus(d);
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(desfase);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zona) {
            return this;
        }
    }

    /** Responde lo que la prueba le programa; por defecto, un resultado cifrado de mentira. */
    private static final class MotorFalso implements MotorIA {
        final Deque<Supplier<EncryptedBlob>> respuestas = new ArrayDeque<>();
        final List<String> llamadas = new ArrayList<>();

        @Override
        public synchronized EncryptedBlob transcribir(String casoId, String segmentoId, SealedEnvelope audio) {
            llamadas.add("transcribir:" + segmentoId);
            return siguiente("transcripcion-" + segmentoId);
        }

        @Override
        public synchronized EncryptedBlob extraer(String casoId, String extraccionId, SealedEnvelope entrada) {
            llamadas.add("extraer:" + extraccionId);
            return siguiente("entidades-" + extraccionId);
        }

        private EncryptedBlob siguiente(String semilla) {
            Supplier<EncryptedBlob> r = respuestas.poll();
            return r != null ? r.get() : cifrado(semilla);
        }
    }

    private static KeyPair par() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void iniciar() {
        bd = new Bd(new BaseDatosExpedientes().recrear());
        VerificationKey clave = VerificationKey.newBuilder().setKeyId(kid).setAlgorithm("Ed25519")
                .setPublicKeySpki(ByteString.copyFrom(claves.getPublic().getEncoded())).build();
        InterceptorSesion sesion = new InterceptorSesion(new ClavesIdentidad(() -> List.of(clave), reloj), reloj);
        planificador = Executors.newScheduledThreadPool(2);
        List<BindableService> servicios = List.of(
                conSesion(new ServicioCasosGrpc(new ServicioCasos(bd, reloj)), sesion),
                conSesion(new ServicioParticipantesGrpc(new ServicioParticipantes(bd, reloj)), sesion),
                conSesion(new ServicioAudienciasGrpc(new ServicioAudiencias(bd, reloj), planificador, reloj,
                        Duration.ofMillis(30), Duration.ofMillis(200), FLUJO_MAXIMO), sesion));
        FirmaHmac firma = new FirmaHmac(SECRETO_BFF, 300, reloj);
        servidor = new ServidorGrpc(servicios, firma, -1, 1 << 20);
        servidor.iniciar();
        canal = ClientInterceptors.intercept(servidor.canalEnMemoria(), new InterceptorClienteFirma(firma));
        cola = new ColaTrabajos(bd, relojCola, motor);
    }

    @AfterEach
    void apagar() {
        servidor.close();
        planificador.shutdownNow();
    }

    private static BindableService conSesion(BindableService servicio, InterceptorSesion sesion) {
        return () -> ServerInterceptors.intercept(servicio, sesion);
    }

    // ------------------------------------------------------------------------ apoyo

    private String token(Persona p) {
        Map<String, Object> carga = new LinkedHashMap<>();
        carga.put("iss", TokenAcceso.EMISOR);
        carga.put("sub", p.cuenta().toString());
        carga.put("org", ORG.toString());
        carga.put("role", p.rol());
        carga.put("email", p.correo());
        carga.put("name", p.nombre());
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

    private Channel como(Persona p) {
        Metadata cabeceras = new Metadata();
        cabeceras.put(Metadatos.AUTORIZACION, "Bearer " + token(p));
        return ClientInterceptors.intercept(canal, MetadataUtils.newAttachHeadersInterceptor(cabeceras));
    }

    private HearingServiceGrpc.HearingServiceBlockingStub audiencias(Persona p) {
        return HearingServiceGrpc.newBlockingStub(como(p));
    }

    private static EncryptedBlob cifrado(String semilla) {
        return EncryptedBlob.newBuilder().setSuite(CipherSuite.CIPHER_SUITE_AES_256_GCM).setKeyId("contenido:v1")
                .setNonce(ByteString.copyFrom(new byte[12]))
                .setCiphertext(ByteString.copyFromUtf8("cifrado-" + semilla + "-0123456789abcdef")).build();
    }

    private static WrappedKey envuelta(String para) {
        byte[] efimera = new byte[65];
        efimera[0] = 0x04;
        return WrappedKey.newBuilder().setSuite(CipherSuite.CIPHER_SUITE_ECDH_P256_HKDF_SHA256_AES_256_GCM)
                .setRecipientKeyId(para).setEphemeralPublicKey(ByteString.copyFrom(efimera))
                .setNonce(ByteString.copyFrom(new byte[12])).setWrappedKey(ByteString.copyFrom(new byte[48])).build();
    }

    private static SealedEnvelope sobre(String semilla) {
        return SealedEnvelope.newBuilder().setPayload(cifrado(semilla)).setProcessorKey(envuelta("motor:0123abcd"))
                .setContentKeyForCase(cifrado("k-" + semilla)).build();
    }

    private static Status.Code codigo(Runnable llamada) {
        return assertThrows(StatusRuntimeException.class, llamada::run).getStatus().getCode();
    }

    private record Escenario(LegalCase caso, Participant asistente, Participant parte) {
    }

    /** Alicia concilia; Bruno es asistente y Carla, parte. Dario no participa. */
    private Escenario escenario() {
        LegalCase caso = CaseServiceGrpc.newBlockingStub(como(alicia)).createCase(CreateCaseRequest.newBuilder()
                .setCaseNumber("CC-2026-0100").setKind(CaseKind.CASE_KIND_CONCILIATION)
                .setDetails(cifrado("detalles")).setWrappedCaseKey(envuelta("cuenta:alicia:v1"))
                .setIdempotencyKey(UUID.randomUUID().toString()).build()).getLegalCase();
        ParticipantServiceGrpc.ParticipantServiceBlockingStub personas = ParticipantServiceGrpc
                .newBlockingStub(como(alicia));
        Participant asistente = personas.inviteParticipant(InviteParticipantRequest.newBuilder()
                .setCaseId(caso.getId()).setEmail(bruno.correo()).setDisplayName("Bruno")
                .setRole(CaseRole.CASE_ROLE_ASSISTANT).setIdempotencyKey(UUID.randomUUID().toString()).build())
                .getParticipant();
        Participant parte = personas.inviteParticipant(InviteParticipantRequest.newBuilder()
                .setCaseId(caso.getId()).setEmail(carla.correo()).setDisplayName("Carla")
                .setRole(CaseRole.CASE_ROLE_PARTY).setSide(PartySide.PARTY_SIDE_CLAIMANT)
                .setCapacity(PartyCapacity.PARTY_CAPACITY_PRINCIPAL)
                .setIdempotencyKey(UUID.randomUUID().toString()).build()).getParticipant();
        // Al entrar con su cuenta, cada invitacion se vincula por correo.
        for (Persona p : List.of(bruno, carla)) {
            CaseServiceGrpc.newBlockingStub(como(p)).listCases(ListCasesRequest.newBuilder()
                    .setPage(PageRequest.newBuilder().setPageSize(10)).build());
        }
        return new Escenario(caso, asistente, parte);
    }

    private Hearing iniciar(Persona p, String casoId) {
        return audiencias(p).startHearing(StartHearingRequest.newBuilder().setCaseId(casoId)
                .setIdempotencyKey(UUID.randomUUID().toString()).build()).getHearing();
    }

    private SubmitAudioSegmentRequest segmento(Hearing h, int orden, String hablante) {
        return SubmitAudioSegmentRequest.newBuilder().setHearingId(h.getId())
                .setSegmentId(UUID.randomUUID().toString()).setSequence(orden).setSpeakerParticipantId(hablante)
                .setDurationMs(12_000).setAudio(sobre("audio-" + orden)).build();
    }

    private AudioSegment unico(Hearing h) {
        List<AudioSegment> lista = audiencias(alicia).listSegments(ListSegmentsRequest.newBuilder()
                .setHearingId(h.getId()).setAfterSequence(-1).build()).getSegmentsList();
        assertEquals(1, lista.size());
        return lista.get(0);
    }

    private <T> T columna(String sql, Class<T> tipo, Object... parametros) {
        return bd.transaccion(c -> Bd.uno(c, sql, f -> java.util.Optional.ofNullable(tipo.cast(f.getObject(1))),
                parametros).flatMap(x -> x).orElse(null));
    }

    // ------------------------------------------------------------------------ pruebas

    @Test
    void soloElConciliadorIniciaYTerminaYHayUnaSolaEnCurso() {
        Escenario e = escenario();
        String casoId = e.caso().getId();
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> iniciar(bruno, casoId)));
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> iniciar(carla, casoId)));
        assertEquals(Status.Code.NOT_FOUND, codigo(() -> iniciar(dario, casoId)));

        StartHearingRequest peticion = StartHearingRequest.newBuilder().setCaseId(casoId)
                .setIdempotencyKey(UUID.randomUUID().toString()).build();
        Hearing h = audiencias(alicia).startHearing(peticion).getHearing();
        assertEquals(HearingState.HEARING_STATE_IN_PROGRESS, h.getState());
        assertEquals(h, audiencias(alicia).startHearing(peticion).getHearing(), "reintento con la misma clave");
        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> iniciar(alicia, casoId)));

        assertEquals(h.getId(), audiencias(carla).getHearing(GetHearingRequest.newBuilder()
                .setHearingId(h.getId()).build()).getHearing().getId(), "las partes la ven");
        assertEquals(Status.Code.NOT_FOUND, codigo(() -> audiencias(dario).getHearing(GetHearingRequest
                .newBuilder().setHearingId(h.getId()).build())));

        EndHearingRequest fin = EndHearingRequest.newBuilder().setHearingId(h.getId()).build();
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> audiencias(bruno).endHearing(fin)));
        Hearing terminada = audiencias(alicia).endHearing(fin).getHearing();
        assertEquals(HearingState.HEARING_STATE_ENDED, terminada.getState());
        assertEquals(terminada, audiencias(alicia).endHearing(fin).getHearing(), "terminar es idempotente");

        Hearing segunda = iniciar(alicia, casoId);
        assertEquals(List.of(segunda.getId(), h.getId()), audiencias(carla).listHearings(ListHearingsRequest
                .newBuilder().setCaseId(casoId).build()).getHearingsList().stream().map(Hearing::getId).toList());
    }

    @Test
    void losSegmentosSonIdempotentesYSoloLosMandaQuienGraba() {
        Escenario e = escenario();
        Hearing h = iniciar(alicia, e.caso().getId());
        SubmitAudioSegmentRequest primero = segmento(h, 0, e.parte().getId());

        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> audiencias(carla).submitAudioSegment(primero)));
        AudioSegment s = audiencias(bruno).submitAudioSegment(primero).getSegment();
        assertEquals(ProcessingState.PROCESSING_STATE_QUEUED, s.getState());
        assertEquals(primero.getAudio().getContentKeyForCase(), s.getContentKeyForCase());
        assertEquals(s, audiencias(bruno).submitAudioSegment(primero).getSegment(), "reintento del mismo audio");
        assertEquals(Status.Code.ALREADY_EXISTS, codigo(() -> audiencias(bruno).submitAudioSegment(
                segmento(h, 0, e.parte().getId()))));

        assertEquals(Status.Code.INVALID_ARGUMENT, codigo(() -> audiencias(alicia).submitAudioSegment(
                segmento(h, 1, UUID.randomUUID().toString()))), "quien habla debe ser participante");
        assertEquals(Status.Code.INVALID_ARGUMENT, codigo(() -> audiencias(alicia).submitAudioSegment(
                segmento(h, 1, e.parte().getId()).toBuilder().setDurationMs(30_001).build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, codigo(() -> audiencias(alicia).submitAudioSegment(
                segmento(h, 1, e.parte().getId()).toBuilder().setAudio(sobre("x").toBuilder()
                        .clearProcessorKey()).build())), "el sobre debe traer la clave para el motor");

        audiencias(alicia).endHearing(EndHearingRequest.newBuilder().setHearingId(h.getId()).build());
        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> audiencias(alicia).submitAudioSegment(
                segmento(h, 1, e.parte().getId()))));
        assertEquals(1, cola.procesarPendientes(10), "lo ya recibido se transcribe aunque la audiencia termine");
    }

    @Test
    void laColaTranscribeYBorraElAudio() {
        Escenario e = escenario();
        Hearing h = iniciar(alicia, e.caso().getId());
        AudioSegment s = audiencias(alicia).submitAudioSegment(segmento(h, 0, e.parte().getId())).getSegment();
        UUID id = UUID.fromString(s.getId());
        assertTrue(columna("select audio from segmento_audio where id = ?", byte[].class, id) != null);

        assertEquals(1, cola.procesarPendientes(10));
        assertEquals(0, cola.procesarPendientes(10), "la cola quedo vacia");
        AudioSegment hecho = unico(h);
        assertEquals(ProcessingState.PROCESSING_STATE_DONE, hecho.getState());
        assertEquals(cifrado("transcripcion-" + id), hecho.getTranscript());
        assertNull(columna("select audio from segmento_audio where id = ?", byte[].class, id), "audio borrado");
        assertEquals("COMPLETADO", columna("select estado from trabajo where clave_idempotencia = ?", String.class,
                id.toString()));
        assertEquals(List.of("transcribir:" + id), motor.llamadas);
    }

    @Test
    void unErrorPasajeroSeReintentaYUnoDefinitivoMarcaElFallo() {
        Escenario e = escenario();
        Hearing h = iniciar(alicia, e.caso().getId());
        AudioSegment s = audiencias(alicia).submitAudioSegment(segmento(h, 0, e.parte().getId())).getSegment();

        motor.respuestas.add(() -> {
            throw Status.UNAVAILABLE.withDescription("motor dormido").asRuntimeException();
        });
        assertEquals(1, cola.procesarPendientes(10));
        assertEquals(ProcessingState.PROCESSING_STATE_PROCESSING, unico(h).getState());
        assertEquals("PENDIENTE", columna("select estado from trabajo where clave_idempotencia = ?", String.class,
                s.getId()));
        assertEquals(0, cola.procesarPendientes(10), "espera antes de reintentar");
        relojCola.adelantar(Duration.ofSeconds(11));
        assertEquals(1, cola.procesarPendientes(10));
        assertEquals(ProcessingState.PROCESSING_STATE_DONE, unico(h).getState());

        AudioSegment otro = audiencias(alicia).submitAudioSegment(segmento(h, 1, e.parte().getId())).getSegment();
        motor.respuestas.add(() -> {
            throw Status.INVALID_ARGUMENT.withDescription("audio ilegible").asRuntimeException();
        });
        assertEquals(1, cola.procesarPendientes(10));
        AudioSegment fallido = audiencias(alicia).listSegments(ListSegmentsRequest.newBuilder()
                .setHearingId(h.getId()).setAfterSequence(0).build()).getSegments(0);
        assertEquals(otro.getId(), fallido.getId());
        assertEquals(ProcessingState.PROCESSING_STATE_FAILED, fallido.getState());
        assertFalse(fallido.hasTranscript());
        assertEquals("FALLIDO", columna("select estado from trabajo where clave_idempotencia = ?", String.class,
                otro.getId()));
        assertNull(columna("select audio from segmento_audio where id = ?", byte[].class,
                UUID.fromString(otro.getId())), "el audio tampoco se guarda si fallo");
    }

    @Test
    void laExtraccionSeRevisaConConcurrenciaOptimista() {
        Escenario e = escenario();
        Hearing h = iniciar(alicia, e.caso().getId());
        GetEntityExtractionRequest ultima = GetEntityExtractionRequest.newBuilder().setHearingId(h.getId()).build();
        assertEquals(Status.Code.NOT_FOUND, codigo(() -> audiencias(alicia).getEntityExtraction(ultima)));

        RequestEntityExtractionRequest pedido = RequestEntityExtractionRequest.newBuilder().setHearingId(h.getId())
                .setExtractionId(UUID.randomUUID().toString()).setInput(sobre("transcripcion")).build();
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> audiencias(carla).requestEntityExtraction(pedido)));
        EntityExtraction x = audiencias(bruno).requestEntityExtraction(pedido).getExtraction();
        assertEquals(ProcessingState.PROCESSING_STATE_QUEUED, x.getState());
        assertEquals(x, audiencias(bruno).requestEntityExtraction(pedido).getExtraction(), "reintento");

        ConfirmEntitiesRequest.Builder confirmar = ConfirmEntitiesRequest.newBuilder().setExtractionId(x.getId())
                .setExpectedRevision(0).setReviewed(cifrado("revisado-1"));
        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> audiencias(alicia).confirmEntities(
                confirmar.build())), "aun no termina");

        assertEquals(1, cola.procesarPendientes(10));
        EntityExtraction lista = audiencias(carla).getEntityExtraction(ultima).getExtraction();
        assertEquals(ProcessingState.PROCESSING_STATE_DONE, lista.getState());
        assertEquals(cifrado("entidades-" + x.getId()), lista.getProposed());

        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> audiencias(carla).confirmEntities(
                confirmar.build())));
        EntityExtraction revisada = audiencias(bruno).confirmEntities(confirmar.build()).getExtraction();
        assertEquals(1, revisada.getRevision());
        assertEquals(e.asistente().getId(), revisada.getReviewedByParticipantId());
        assertEquals(Status.Code.ABORTED, codigo(() -> audiencias(alicia).confirmEntities(confirmar
                .setReviewed(cifrado("revisado-2")).build())), "otra persona ya guardo la revision 1");
        assertEquals(2, audiencias(alicia).confirmEntities(confirmar.setExpectedRevision(1).build())
                .getExtraction().getRevision());
    }

    @Test
    void elFlujoDeEventosMandaLosCambiosYCierraAlTerminar() {
        Escenario e = escenario();
        Hearing h = iniciar(alicia, e.caso().getId());
        WatchHearingEventsRequest mirar = WatchHearingEventsRequest.newBuilder().setHearingId(h.getId()).build();
        assertEquals(Status.Code.NOT_FOUND, codigo(() -> audiencias(dario).watchHearingEvents(mirar).hasNext()));

        AudioSegment s = audiencias(alicia).submitAudioSegment(segmento(h, 0, e.parte().getId())).getSegment();
        cola.procesarPendientes(10);
        audiencias(alicia).endHearing(EndHearingRequest.newBuilder().setHearingId(h.getId()).build());

        List<WatchHearingEventsResponse> eventos = new ArrayList<>();
        audiencias(carla).watchHearingEvents(mirar).forEachRemaining(eventos::add);
        List<WatchHearingEventsResponse.EventCase> tipos = eventos.stream()
                .map(WatchHearingEventsResponse::getEventCase).toList();
        // recibido, procesando, hecho y el fin de la audiencia
        assertEquals(List.of(WatchHearingEventsResponse.EventCase.SEGMENT_UPDATED,
                WatchHearingEventsResponse.EventCase.SEGMENT_UPDATED,
                WatchHearingEventsResponse.EventCase.SEGMENT_UPDATED,
                WatchHearingEventsResponse.EventCase.HEARING_ENDED), tipos);
        assertEquals(s.getId(), eventos.get(0).getSegmentUpdated().getId());
        assertEquals(ProcessingState.PROCESSING_STATE_DONE, eventos.get(2).getSegmentUpdated().getState(),
                "cada evento lleva el estado actual del segmento");
        long[] secuencias = eventos.stream().mapToLong(WatchHearingEventsResponse::getEventSequence).toArray();
        long[] ordenadas = secuencias.clone();
        java.util.Arrays.sort(ordenadas);
        assertArrayEquals(ordenadas, secuencias);

        List<WatchHearingEventsResponse> resto = new ArrayList<>();
        audiencias(alicia).watchHearingEvents(mirar.toBuilder().setAfterEventSequence(secuencias[2]).build())
                .forEachRemaining(resto::add);
        assertEquals(1, resto.size(), "se reanuda desde el ultimo evento recibido");
        assertEquals(HearingState.HEARING_STATE_ENDED, resto.get(0).getHearingEnded().getState());
    }

    @Test
    void elFlujoDeUnaAudienciaEnCursoMandaLatidosYLosCambiosNuevos() {
        Escenario e = escenario();
        Hearing h = iniciar(alicia, e.caso().getId());
        Iterator<WatchHearingEventsResponse> flujo = audiencias(carla).watchHearingEvents(
                WatchHearingEventsRequest.newBuilder().setHearingId(h.getId()).build());

        WatchHearingEventsResponse latido = flujo.next();
        assertTrue(latido.hasHeartbeat(), "sin cambios, llega un latido");
        assertEquals(0, latido.getEventSequence());

        AudioSegment s = audiencias(alicia).submitAudioSegment(segmento(h, 0, e.parte().getId())).getSegment();
        WatchHearingEventsResponse nuevo = flujo.next();
        while (nuevo.hasHeartbeat()) {
            nuevo = flujo.next();
        }
        assertEquals(s.getId(), nuevo.getSegmentUpdated().getId());
        audiencias(alicia).endHearing(EndHearingRequest.newBuilder().setHearingId(h.getId()).build());
        WatchHearingEventsResponse ultimo = nuevo;
        while (flujo.hasNext()) {
            ultimo = flujo.next();
        }
        assertTrue(ultimo.hasHearingEnded(), "el flujo cierra despues del fin de la audiencia");
    }

    @Test
    void elFlujoSeCierraSoloAlTiempoMaximoAunqueLaAudienciaSigaEnCurso() {
        Escenario e = escenario();
        Hearing h = iniciar(alicia, e.caso().getId());
        long inicio = System.nanoTime();
        List<WatchHearingEventsResponse> eventos = new ArrayList<>();
        audiencias(carla).watchHearingEvents(WatchHearingEventsRequest.newBuilder().setHearingId(h.getId()).build())
                .forEachRemaining(eventos::add);
        Duration duro = Duration.ofNanos(System.nanoTime() - inicio);
        assertTrue(duro.compareTo(FLUJO_MAXIMO) >= 0 && duro.compareTo(FLUJO_MAXIMO.multipliedBy(3)) < 0,
                "cierra al cumplir el maximo (la pasarela gRPC-Web corta a los 60 s): " + duro);
        assertFalse(eventos.isEmpty());
        assertTrue(eventos.stream().allMatch(WatchHearingEventsResponse::hasHeartbeat), "solo latidos");
    }
}

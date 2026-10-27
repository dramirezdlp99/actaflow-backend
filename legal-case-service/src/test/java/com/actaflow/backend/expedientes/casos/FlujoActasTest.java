package com.actaflow.backend.expedientes.casos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.actaflow.backend.expedientes.aplicacion.ColaTrabajos;
import com.actaflow.backend.expedientes.aplicacion.MotorIA;
import com.actaflow.backend.expedientes.aplicacion.ServicioActas;
import com.actaflow.backend.expedientes.aplicacion.ServicioCasos;
import com.actaflow.backend.expedientes.aplicacion.ServicioColaboracion;
import com.actaflow.backend.expedientes.aplicacion.ServicioParticipantes;
import com.actaflow.backend.expedientes.grpc.ServicioActasGrpc;
import com.actaflow.backend.expedientes.grpc.ServicioCasosGrpc;
import com.actaflow.backend.expedientes.grpc.ServicioColaboracionGrpc;
import com.actaflow.backend.expedientes.grpc.ServicioParticipantesGrpc;
import com.actaflow.backend.expedientes.sello.Sellador;
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
import com.actaflow.contracts.collaboration.v1.CollaborationServiceGrpc;
import com.actaflow.contracts.collaboration.v1.CompactDocumentRequest;
import com.actaflow.contracts.collaboration.v1.EncryptedUpdate;
import com.actaflow.contracts.collaboration.v1.GetDocumentStateRequest;
import com.actaflow.contracts.collaboration.v1.GetDocumentStateResponse;
import com.actaflow.contracts.collaboration.v1.PushUpdatesRequest;
import com.actaflow.contracts.collaboration.v1.SequencedUpdate;
import com.actaflow.contracts.common.v1.PageRequest;
import com.actaflow.contracts.crypto.v1.CipherSuite;
import com.actaflow.contracts.crypto.v1.EncryptedBlob;
import com.actaflow.contracts.crypto.v1.SealedEnvelope;
import com.actaflow.contracts.crypto.v1.WrappedKey;
import com.actaflow.contracts.identity.v1.VerificationKey;
import com.actaflow.contracts.minutes.v1.AcceptFinalDocumentRequest;
import com.actaflow.contracts.minutes.v1.CloseMinuteRequest;
import com.actaflow.contracts.minutes.v1.CreateMinuteRequest;
import com.actaflow.contracts.minutes.v1.FillMinuteRequest;
import com.actaflow.contracts.minutes.v1.FinalDocument;
import com.actaflow.contracts.minutes.v1.GetFinalDocumentRequest;
import com.actaflow.contracts.minutes.v1.GetMinuteRequest;
import com.actaflow.contracts.minutes.v1.ListMinutesRequest;
import com.actaflow.contracts.minutes.v1.Minute;
import com.actaflow.contracts.minutes.v1.MinuteServiceGrpc;
import com.actaflow.contracts.minutes.v1.MinuteState;
import com.actaflow.contracts.minutes.v1.VerifyDocumentRequest;
import com.actaflow.contracts.minutes.v1.VerifyDocumentResponse;
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
 * Actas de punta a punta por gRPC (en memoria) con PostgreSQL real y un motor falso que "genera"
 * el PDF: permisos, cierre idempotente, sello CMS sobre el hash, aceptacion con la prueba de
 * presencia de identity y verificacion publica.
 */
class FlujoActasTest {

    private static final String SECRETO_BFF = "secreto-de-prueba-publico-solo-para-tests-0123456789";
    private static final String SECRETO_SELLO = "secreto-del-sello-de-prueba-publico-solo-para-tests-01";
    private static final UUID ORG = UUID.randomUUID();
    private static final byte[] PDF = "%PDF-1.4 acta de prueba".getBytes(StandardCharsets.US_ASCII);

    private final KeyPair claves = par();
    private final String kid = TokenAcceso.idDeClave(claves.getPublic());
    private final Clock reloj = Clock.systemUTC();
    private final Deque<Supplier<MotorIA.Renderizado>> respuestas = new ArrayDeque<>();
    private final Sellador sellador = new Sellador(SECRETO_SELLO);
    private ServidorGrpc servidor;
    private Channel canal;
    private Bd bd;
    private ColaTrabajos cola;

    private record Persona(UUID cuenta, String correo, String nombre) {
    }

    private final Persona alicia = new Persona(UUID.randomUUID(), "alicia@prueba.test", "Alicia");
    private final Persona bruno = new Persona(UUID.randomUUID(), "bruno@prueba.test", "Bruno");
    private final Persona carla = new Persona(UUID.randomUUID(), "carla@prueba.test", "Carla");
    private final Persona dario = new Persona(UUID.randomUUID(), "dario@prueba.test", "Dario");
    private final Persona elena = new Persona(UUID.randomUUID(), "elena@prueba.test", "Elena");

    private static KeyPair par() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** El motor falso: devuelve un PDF "cifrado" y el SHA-256 del PDF en claro. */
    private final MotorIA motor = new MotorIA() {
        @Override
        public EncryptedBlob transcribir(String casoId, String segmentoId, SealedEnvelope audio) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EncryptedBlob extraer(String casoId, String extraccionId, SealedEnvelope entrada) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Renderizado renderizar(String casoId, String actaId, SealedEnvelope contenido) {
            Supplier<Renderizado> r = respuestas.poll();
            return r != null ? r.get() : new Renderizado(cifrado("pdf-" + actaId), sha(PDF), 2);
        }
    };

    @BeforeEach
    void iniciar() {
        bd = new Bd(new BaseDatosExpedientes().recrear());
        VerificationKey clave = VerificationKey.newBuilder().setKeyId(kid).setAlgorithm("Ed25519")
                .setPublicKeySpki(ByteString.copyFrom(claves.getPublic().getEncoded())).build();
        ClavesIdentidad identidad = new ClavesIdentidad(() -> List.of(clave), reloj);
        InterceptorSesion sesion = new InterceptorSesion(identidad, reloj);
        List<BindableService> servicios = List.of(
                conSesion(new ServicioCasosGrpc(new ServicioCasos(bd, reloj)), sesion),
                conSesion(new ServicioParticipantesGrpc(new ServicioParticipantes(bd, reloj)), sesion),
                conSesion(new ServicioColaboracionGrpc(new ServicioColaboracion(bd, reloj)), sesion),
                conSesion(new ServicioActasGrpc(new ServicioActas(bd, reloj, identidad)),
                        new InterceptorSesion(identidad, reloj, ServicioActasGrpc.PUBLICOS)));
        FirmaHmac firma = new FirmaHmac(SECRETO_BFF, 300, reloj);
        servidor = new ServidorGrpc(servicios, firma, -1, 1 << 20);
        servidor.iniciar();
        canal = ClientInterceptors.intercept(servidor.canalEnMemoria(), new InterceptorClienteFirma(firma));
        cola = new ColaTrabajos(bd, reloj, motor, sellador);
    }

    @AfterEach
    void apagar() {
        servidor.close();
    }

    private static BindableService conSesion(BindableService servicio, InterceptorSesion sesion) {
        return () -> ServerInterceptors.intercept(servicio, sesion);
    }

    // ------------------------------------------------------------------------ apoyo

    private String firmar(Map<String, Object> carga) {
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

    private String token(Persona p) {
        Map<String, Object> carga = new LinkedHashMap<>();
        carga.put("iss", TokenAcceso.EMISOR);
        carga.put("sub", p.cuenta().toString());
        carga.put("org", ORG.toString());
        carga.put("role", "MEMBER");
        carga.put("email", p.correo());
        carga.put("name", p.nombre());
        carga.put("exp", Instant.now().getEpochSecond() + 600);
        return firmar(carga);
    }

    /** Lo que entrega identity en ConfirmPresence. */
    private String presencia(Persona p, String proposito, long segundos) {
        Map<String, Object> carga = new LinkedHashMap<>();
        carga.put("iss", TokenAcceso.EMISOR);
        carga.put("sub", p.cuenta().toString());
        carga.put(TokenAcceso.RECLAMO_PROPOSITO, proposito);
        carga.put("exp", Instant.now().getEpochSecond() + segundos);
        return firmar(carga);
    }

    private Channel como(Persona p) {
        Metadata cabeceras = new Metadata();
        cabeceras.put(Metadatos.AUTORIZACION, "Bearer " + token(p));
        return ClientInterceptors.intercept(canal, MetadataUtils.newAttachHeadersInterceptor(cabeceras));
    }

    private MinuteServiceGrpc.MinuteServiceBlockingStub actas(Persona p) {
        return MinuteServiceGrpc.newBlockingStub(como(p));
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

    private static String sha(byte[] datos) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Status.Code codigo(Runnable llamada) {
        try {
            llamada.run();
        } catch (StatusRuntimeException e) {
            return e.getStatus().getCode();
        }
        return Status.Code.OK;
    }

    private record Escenario(LegalCase caso, Participant parte, UUID plantilla) {
    }

    /** Alicia concilia, Bruno es asistente, Carla es parte; Dario no participa. */
    private Escenario escenario() {
        LegalCase caso = CaseServiceGrpc.newBlockingStub(como(alicia)).createCase(CreateCaseRequest.newBuilder()
                .setCaseNumber("CC-2026-0500").setKind(CaseKind.CASE_KIND_CONCILIATION)
                .setDetails(cifrado("detalles")).setWrappedCaseKey(envuelta("cuenta:alicia:v1"))
                .setIdempotencyKey(UUID.randomUUID().toString()).build()).getLegalCase();
        ParticipantServiceGrpc.ParticipantServiceBlockingStub personas = ParticipantServiceGrpc
                .newBlockingStub(como(alicia));
        personas.inviteParticipant(InviteParticipantRequest.newBuilder().setCaseId(caso.getId())
                .setEmail(bruno.correo()).setDisplayName("Bruno").setRole(CaseRole.CASE_ROLE_ASSISTANT)
                .setIdempotencyKey(UUID.randomUUID().toString()).build());
        Participant parte = personas.inviteParticipant(InviteParticipantRequest.newBuilder()
                .setCaseId(caso.getId()).setEmail(carla.correo()).setDisplayName("Carla")
                .setRole(CaseRole.CASE_ROLE_PARTY).setSide(PartySide.PARTY_SIDE_CLAIMANT)
                .setCapacity(PartyCapacity.PARTY_CAPACITY_PRINCIPAL)
                .setIdempotencyKey(UUID.randomUUID().toString()).build()).getParticipant();
        for (Persona p : List.of(bruno, carla)) {
            CaseServiceGrpc.newBlockingStub(como(p)).listCases(ListCasesRequest.newBuilder()
                    .setPage(PageRequest.newBuilder().setPageSize(10)).build());
        }
        UUID plantilla = bd.transaccion(c -> Bd.uno(c, "select id from plantilla where slug = ?",
                f -> Bd.uuid(f, "id"), "acta-conciliacion-arrendamiento").orElseThrow());
        return new Escenario(caso, parte, plantilla);
    }

    private CreateMinuteRequest.Builder nueva(Escenario e) {
        return CreateMinuteRequest.newBuilder().setCaseId(e.caso().getId()).setTemplateId(e.plantilla().toString())
                .setTemplateVersion(1).setIdempotencyKey(UUID.randomUUID().toString());
    }

    private CloseMinuteRequest cierre(Minute acta) {
        return CloseMinuteRequest.newBuilder().setMinuteId(acta.getId()).setFinalContent(sobre("final"))
                .setIdempotencyKey(UUID.randomUUID().toString()).build();
    }

    private FinalDocument documento(Persona p, Minute acta) {
        return actas(p).getFinalDocument(GetFinalDocumentRequest.newBuilder().setMinuteId(acta.getId()).build())
                .getDocument();
    }

    private AcceptFinalDocumentRequest aceptacion(FinalDocument d, String prueba) {
        return AcceptFinalDocumentRequest.newBuilder().setDocumentId(d.getId())
                .setSha256Hex(d.getSeal().getSha256Hex()).setTotpCode("123456").setPresenceToken(prueba).build();
    }

    private Minute estado(Minute acta) {
        return actas(alicia).getMinute(GetMinuteRequest.newBuilder().setMinuteId(acta.getId()).build()).getMinute();
    }

    // ------------------------------------------------------------------------ pruebas

    @Test
    void soloElConciliadorCreaYCierraConPlantillasPublicadas() {
        Escenario e = escenario();
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> actas(bruno).createMinute(nueva(e).build())));
        assertEquals(Status.Code.NOT_FOUND, codigo(() -> actas(dario).createMinute(nueva(e).build())));
        assertEquals(Status.Code.NOT_FOUND, codigo(() -> actas(alicia).createMinute(nueva(e)
                .setTemplateId(UUID.randomUUID().toString()).build())));

        CreateMinuteRequest pedido = nueva(e).build();
        Minute acta = actas(alicia).createMinute(pedido).getMinute();
        assertEquals(MinuteState.MINUTE_STATE_DRAFT, acta.getState());
        assertEquals(acta, actas(alicia).createMinute(pedido).getMinute(), "reintento con la misma clave");
        assertEquals(List.of(acta.getId()), actas(carla).listMinutes(ListMinutesRequest.newBuilder()
                .setCaseId(e.caso().getId()).build()).getMinutesList().stream().map(Minute::getId).toList());
        assertEquals(Status.Code.NOT_FOUND, codigo(() -> documento(carla, acta)), "aun no hay documento");
        assertEquals(Status.Code.UNIMPLEMENTED, codigo(() -> actas(alicia).fillMinute(FillMinuteRequest
                .newBuilder().setMinuteId(acta.getId()).setInput(sobre("x")).build())), "lo llena el navegador");

        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> actas(bruno).closeMinute(cierre(acta))));
        assertEquals(Status.Code.INVALID_ARGUMENT, codigo(() -> actas(alicia).closeMinute(cierre(acta)
                .toBuilder().setFinalContent(sobre("x").toBuilder().clearProcessorKey()).build())));
        CloseMinuteRequest cerrar = cierre(acta);
        Minute cerrada = actas(alicia).closeMinute(cerrar).getMinute();
        assertEquals(MinuteState.MINUTE_STATE_SEALING, cerrada.getState());
        assertTrue(cerrada.hasClosedAt());
        assertEquals(cerrada, actas(alicia).closeMinute(cerrar).getMinute(), "reintento del cierre");
        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> actas(alicia).closeMinute(cierre(acta))));
    }

    @Test
    void laColaGeneraElPdfLoSellaYPideLaAceptacionDeLasPartes() throws Exception {
        Escenario e = escenario();
        Minute acta = actas(alicia).createMinute(nueva(e).build()).getMinute();
        actas(alicia).closeMinute(cierre(acta));
        assertEquals(1, cola.procesarPendientes(10));

        assertEquals(MinuteState.MINUTE_STATE_AWAITING_ACCEPTANCE, estado(acta).getState());
        FinalDocument d = documento(carla, acta);
        assertEquals(cifrado("pdf-" + acta.getId()), d.getPdf());
        assertEquals(sobre("final").getContentKeyForCase(), d.getContentKeyForCase(), "K, cifrada para el caso");
        assertEquals(sha(PDF), d.getSeal().getSha256Hex());
        assertEquals(sellador.huellaCertificado(), d.getSeal().getCertificateFingerprintSha256());
        assertEquals(List.of(e.parte().getId()), d.getPendingParticipantIdsList());
        assertTrue(d.getAcceptancesList().isEmpty());

        // La firma CMS cubre los atributos con el SHA-256 del PDF y la hace el certificado de ActaFlow.
        byte[] cms = d.getSeal().getCmsSignature().toByteArray();
        byte[] hash = HexFormat.of().parseHex(d.getSeal().getSha256Hex());
        assertTrue(contiene(cms, hash), "el resumen firmado es el del PDF");
        assertTrue(contiene(cms, sellador.certificado().getEncoded()), "el certificado va dentro");
        boolean contenido = bd.transaccion(c -> Bd.uno(c, "select contenido_final is not null as hay from acta"
                + " where id = ?", f -> f.getBoolean("hay"), UUID.fromString(acta.getId())).orElseThrow());
        assertFalse(contenido, "el contenido sellado se borra al tener el PDF");

        VerifyDocumentResponse verificado = MinuteServiceGrpc.newBlockingStub(canal).verifyDocument(
                VerifyDocumentRequest.newBuilder().setSha256Hex(sha(PDF)).build());
        assertTrue(verificado.getAuthentic(), "cualquiera verifica, sin sesion");
        assertEquals("CC-2026-0500", verificado.getCaseNumber());
        assertEquals(0, verificado.getAcceptanceCount());
        assertFalse(MinuteServiceGrpc.newBlockingStub(canal).verifyDocument(VerifyDocumentRequest.newBuilder()
                .setSha256Hex("0".repeat(64)).build()).getAuthentic());
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> MinuteServiceGrpc.newBlockingStub(canal)
                .getFinalDocument(GetFinalDocumentRequest.newBuilder().setMinuteId(acta.getId()).build())));
    }

    @Test
    void laParteAceptaConLaPruebaDePresenciaDeEsteDocumento() {
        Escenario e = escenario();
        Participant otra = ParticipantServiceGrpc.newBlockingStub(como(alicia)).inviteParticipant(
                InviteParticipantRequest.newBuilder().setCaseId(e.caso().getId()).setEmail(elena.correo())
                        .setDisplayName("Elena").setRole(CaseRole.CASE_ROLE_PARTY)
                        .setSide(PartySide.PARTY_SIDE_RESPONDENT).setCapacity(PartyCapacity.PARTY_CAPACITY_PRINCIPAL)
                        .setIdempotencyKey(UUID.randomUUID().toString()).build()).getParticipant();
        CaseServiceGrpc.newBlockingStub(como(elena)).listCases(ListCasesRequest.newBuilder()
                .setPage(PageRequest.newBuilder().setPageSize(10)).build());
        Minute acta = actas(alicia).createMinute(nueva(e).build()).getMinute();
        actas(alicia).closeMinute(cierre(acta));
        cola.procesarPendientes(10);
        FinalDocument d = documento(carla, acta);
        String proposito = ServicioActas.PROPOSITO_ACEPTAR + d.getId() + ":" + d.getSeal().getSha256Hex();

        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> actas(carla).acceptFinalDocument(aceptacion(d,
                presencia(carla, "accept-minute:otro", 120)))), "otro proposito");
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> actas(carla).acceptFinalDocument(aceptacion(d,
                presencia(alicia, proposito, 120)))), "la prueba de otra persona");
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> actas(carla).acceptFinalDocument(aceptacion(d,
                token(carla)))), "un token de acceso no es prueba de presencia");
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> actas(carla).acceptFinalDocument(aceptacion(d,
                presencia(carla, proposito, -1)))), "vencida");
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> actas(alicia).acceptFinalDocument(aceptacion(d,
                presencia(alicia, proposito, 120)))), "solo aceptan las partes");

        FinalDocument aceptado = actas(carla).acceptFinalDocument(aceptacion(d, presencia(carla, proposito, 120)))
                .getDocument();
        assertEquals(List.of(e.parte().getId()), aceptado.getAcceptancesList().stream()
                .map(a -> a.getParticipantId()).toList());
        assertEquals(List.of(otra.getId()), aceptado.getPendingParticipantIdsList());
        assertEquals(MinuteState.MINUTE_STATE_AWAITING_ACCEPTANCE, estado(acta).getState(), "falta Elena");

        FinalDocument completo = actas(elena).acceptFinalDocument(aceptacion(d, presencia(elena, proposito, 120)))
                .getDocument();
        assertTrue(completo.getPendingParticipantIdsList().isEmpty());
        assertEquals(MinuteState.MINUTE_STATE_FINAL, estado(acta).getState(), "aceptaron todas las partes");
        assertEquals(completo, actas(carla).acceptFinalDocument(aceptacion(d, presencia(carla, proposito, 120)))
                .getDocument(), "aceptar otra vez no cambia nada");
        assertEquals(2, MinuteServiceGrpc.newBlockingStub(canal).verifyDocument(VerifyDocumentRequest.newBuilder()
                .setSha256Hex(d.getSeal().getSha256Hex()).build()).getAcceptanceCount());
    }

    @Test
    void siElMotorFallaElActaVuelveABorradorYSePuedeCerrarOtraVez() {
        Escenario e = escenario();
        Minute acta = actas(alicia).createMinute(nueva(e).build()).getMinute();
        actas(alicia).closeMinute(cierre(acta));
        respuestas.add(() -> {
            throw Status.INVALID_ARGUMENT.withDescription("contenido invalido").asRuntimeException();
        });
        assertEquals(1, cola.procesarPendientes(10));
        assertEquals(MinuteState.MINUTE_STATE_DRAFT, estado(acta).getState());
        assertFalse(estado(acta).hasClosedAt());

        actas(alicia).closeMinute(cierre(acta));
        assertEquals(1, cola.procesarPendientes(10), "el trabajo se reactiva al cerrar otra vez");
        assertEquals(MinuteState.MINUTE_STATE_AWAITING_ACCEPTANCE, estado(acta).getState());
    }

    private static boolean contiene(byte[] datos, byte[] buscado) {
        outer:
        for (int i = 0; i <= datos.length - buscado.length; i++) {
            for (int j = 0; j < buscado.length; j++) {
                if (datos[i + j] != buscado[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------ borrador colaborativo

    private CollaborationServiceGrpc.CollaborationServiceBlockingStub colaboracion(Persona p) {
        return CollaborationServiceGrpc.newBlockingStub(como(p));
    }

    private static EncryptedUpdate cambio(String id) {
        return EncryptedUpdate.newBuilder().setUpdateId(id).setUpdate(cifrado("cambio-" + id)).build();
    }

    private List<Long> enviar(Persona p, Minute acta, EncryptedUpdate... cambios) {
        return colaboracion(p).pushUpdates(PushUpdatesRequest.newBuilder().setMinuteId(acta.getId())
                .addAllUpdates(List.of(cambios)).build()).getSequencesList();
    }

    private GetDocumentStateResponse leer(Persona p, Minute acta, long despues) {
        return colaboracion(p).getDocumentState(GetDocumentStateRequest.newBuilder().setMinuteId(acta.getId())
                .setAfterSequence(despues).build());
    }

    @Test
    void elBorradorSeOrdenaEnElServidorSinDuplicarYSoloLoEditanConciliadorYAsistentes() {
        Escenario e = escenario();
        Minute acta = actas(alicia).createMinute(nueva(e).build()).getMinute();
        String uno = UUID.randomUUID().toString();
        assertEquals(List.of(1L, 2L), enviar(alicia, acta, cambio(uno), cambio(UUID.randomUUID().toString())));
        assertEquals(List.of(3L), enviar(bruno, acta, cambio(UUID.randomUUID().toString())), "el asistente edita");
        assertEquals(Status.Code.PERMISSION_DENIED,
                codigo(() -> enviar(carla, acta, cambio(UUID.randomUUID().toString()))), "la parte solo mira");
        assertEquals(Status.Code.NOT_FOUND, codigo(() -> leer(dario, acta, 0)));

        // La bandeja de salida sin conexion reenvia: mismo update_id, mismo numero, nada nuevo.
        assertEquals(List.of(1L), enviar(alicia, acta, cambio(uno)));
        GetDocumentStateResponse todo = leer(carla, acta, 0);
        assertEquals(List.of(1L, 2L, 3L), todo.getUpdatesList().stream().map(SequencedUpdate::getSequence).toList());
        assertFalse(todo.hasSnapshot());
        assertEquals(List.of(3L), leer(carla, acta, 2).getUpdatesList().stream()
                .map(SequencedUpdate::getSequence).toList());

        // Compactar: la instantanea reemplaza los cambios que cubre.
        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> colaboracion(alicia).compactDocument(
                CompactDocumentRequest.newBuilder().setMinuteId(acta.getId()).setSnapshot(cifrado("foto"))
                        .setCoversSequence(9).build())));
        assertEquals(2, colaboracion(bruno).compactDocument(CompactDocumentRequest.newBuilder()
                .setMinuteId(acta.getId()).setSnapshot(cifrado("foto")).setCoversSequence(2).build())
                .getRemovedUpdates());
        GetDocumentStateResponse despues = leer(carla, acta, 0);
        assertTrue(despues.hasSnapshot());
        assertEquals(2, despues.getSnapshotSequence());
        assertEquals(List.of(3L), despues.getUpdatesList().stream().map(SequencedUpdate::getSequence).toList());
        assertFalse(leer(carla, acta, 3).hasSnapshot(), "quien ya va al dia no recibe la instantanea");

        // Cerrada el acta, el borrador ya no cambia (pero un reenvio de algo ya guardado se reconoce).
        actas(alicia).closeMinute(cierre(acta));
        assertEquals(Status.Code.FAILED_PRECONDITION,
                codigo(() -> enviar(alicia, acta, cambio(UUID.randomUUID().toString()))));
        assertEquals(List.of(1L), enviar(alicia, acta, cambio(uno)));
    }
}

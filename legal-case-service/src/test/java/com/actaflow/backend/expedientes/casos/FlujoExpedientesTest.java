package com.actaflow.backend.expedientes.casos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.function.Function;

import com.actaflow.backend.expedientes.aplicacion.ServicioCasos;
import com.actaflow.backend.expedientes.aplicacion.ServicioParticipantes;
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
import com.actaflow.contracts.cases.v1.CaseEventKind;
import com.actaflow.contracts.cases.v1.CaseKind;
import com.actaflow.contracts.cases.v1.CaseRole;
import com.actaflow.contracts.cases.v1.CaseServiceGrpc;
import com.actaflow.contracts.cases.v1.CaseStatus;
import com.actaflow.contracts.cases.v1.ChangeCaseStatusRequest;
import com.actaflow.contracts.cases.v1.ChangeParticipantRoleRequest;
import com.actaflow.contracts.cases.v1.CreateCaseRequest;
import com.actaflow.contracts.cases.v1.CreateCaseResponse;
import com.actaflow.contracts.cases.v1.GetCaseRequest;
import com.actaflow.contracts.cases.v1.GrantCaseKeyRequest;
import com.actaflow.contracts.cases.v1.InviteParticipantRequest;
import com.actaflow.contracts.cases.v1.LegalCase;
import com.actaflow.contracts.cases.v1.ListCaseEventsRequest;
import com.actaflow.contracts.cases.v1.ListCasesRequest;
import com.actaflow.contracts.cases.v1.ListCasesResponse;
import com.actaflow.contracts.cases.v1.ListParticipantsRequest;
import com.actaflow.contracts.cases.v1.Participant;
import com.actaflow.contracts.cases.v1.ParticipantServiceGrpc;
import com.actaflow.contracts.cases.v1.ParticipantState;
import com.actaflow.contracts.cases.v1.PartyCapacity;
import com.actaflow.contracts.cases.v1.PartySide;
import com.actaflow.contracts.cases.v1.RemoveParticipantRequest;
import com.actaflow.contracts.cases.v1.UpdateCaseRequest;
import com.actaflow.contracts.common.v1.FieldViolations;
import com.actaflow.contracts.common.v1.PageRequest;
import com.actaflow.contracts.crypto.v1.CipherSuite;
import com.actaflow.contracts.crypto.v1.EncryptedBlob;
import com.actaflow.contracts.crypto.v1.WrappedKey;
import com.actaflow.contracts.identity.v1.VerificationKey;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;

import io.grpc.BindableService;
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
 * Expedientes de punta a punta por gRPC (en memoria), con la firma HMAC del BFF, tokens Ed25519
 * como los de identity-service y PostgreSQL real (esquema legal_case_prueba).
 */
class FlujoExpedientesTest {

    private static final String SECRETO_BFF = "secreto-de-prueba-publico-solo-para-tests-0123456789";
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTRA_ORG = UUID.randomUUID();

    private final KeyPair claves = par();
    private final String kid = TokenAcceso.idDeClave(claves.getPublic());
    private final Clock reloj = Clock.systemUTC();
    private ServidorGrpc servidor;
    private Channel canal;

    private record Persona(UUID cuenta, UUID org, String rol, String correo, String nombre) {
    }

    private final Persona alicia = new Persona(UUID.randomUUID(), ORG, "MEMBER", "alicia@prueba.test", "Alicia");
    private final Persona bruno = new Persona(UUID.randomUUID(), ORG, "MEMBER", "bruno@prueba.test", "Bruno");
    private final Persona carla = new Persona(UUID.randomUUID(), ORG, "EXTERNAL", "carla@prueba.test", "Carla");
    private final Persona dario = new Persona(UUID.randomUUID(), OTRA_ORG, "MEMBER", "dario@otra.test", "Dario");

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
        InterceptorSesion sesion = new InterceptorSesion(new ClavesIdentidad(() -> List.of(clave), reloj), reloj);
        List<BindableService> servicios = List.of(
                conSesion(new ServicioCasosGrpc(new ServicioCasos(bd, reloj)), sesion),
                conSesion(new ServicioParticipantesGrpc(new ServicioParticipantes(bd, reloj)), sesion));
        FirmaHmac firma = new FirmaHmac(SECRETO_BFF, 300, reloj);
        servidor = new ServidorGrpc(servicios, firma, -1, 1 << 20);
        servidor.iniciar();
        canal = ClientInterceptors.intercept(servidor.canalEnMemoria(), new InterceptorClienteFirma(firma));
    }

    @AfterEach
    void apagar() {
        servidor.close();
    }

    private static BindableService conSesion(BindableService servicio, InterceptorSesion sesion) {
        return () -> ServerInterceptors.intercept(servicio, sesion);
    }

    // ------------------------------------------------------------------------ apoyo

    private String token(Persona p, long segundos) {
        Map<String, Object> carga = new LinkedHashMap<>();
        carga.put("iss", TokenAcceso.EMISOR);
        carga.put("sub", p.cuenta().toString());
        carga.put("org", p.org().toString());
        carga.put("role", p.rol());
        carga.put("email", p.correo());
        carga.put("name", p.nombre());
        carga.put("exp", Instant.now().getEpochSecond() + segundos);
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

    private Channel como(String token) {
        Metadata cabeceras = new Metadata();
        cabeceras.put(Metadatos.AUTORIZACION, "Bearer " + token);
        return ClientInterceptors.intercept(canal, MetadataUtils.newAttachHeadersInterceptor(cabeceras));
    }

    private CaseServiceGrpc.CaseServiceBlockingStub casos(Persona p) {
        return CaseServiceGrpc.newBlockingStub(como(token(p, 600)));
    }

    private ParticipantServiceGrpc.ParticipantServiceBlockingStub personas(Persona p) {
        return ParticipantServiceGrpc.newBlockingStub(como(token(p, 600)));
    }

    private static EncryptedBlob detalles(String semilla) {
        return EncryptedBlob.newBuilder().setSuite(CipherSuite.CIPHER_SUITE_AES_256_GCM).setKeyId("case:v1")
                .setNonce(ByteString.copyFrom(new byte[12]))
                .setCiphertext(ByteString.copyFromUtf8("cifrado-" + semilla + "-0123456789abcdef")).build();
    }

    private static WrappedKey envuelta(String para) {
        byte[] efimera = new byte[65];
        efimera[0] = 0x04;
        return WrappedKey.newBuilder().setSuite(CipherSuite.CIPHER_SUITE_ECDH_P256_HKDF_SHA256_AES_256_GCM)
                .setRecipientKeyId("cuenta:" + para + ":v1").setEphemeralPublicKey(ByteString.copyFrom(efimera))
                .setNonce(ByteString.copyFrom(new byte[12])).setWrappedKey(ByteString.copyFrom(new byte[48])).build();
    }

    private CreateCaseRequest.Builder nuevo(String radicado) {
        return CreateCaseRequest.newBuilder().setCaseNumber(radicado).setKind(CaseKind.CASE_KIND_CONCILIATION)
                .setDetails(detalles(radicado)).setWrappedCaseKey(envuelta("alicia"))
                .setIdempotencyKey(UUID.randomUUID().toString());
    }

    private LegalCase crear(Persona p, String radicado) {
        return casos(p).createCase(nuevo(radicado).build()).getLegalCase();
    }

    private static Status.Code codigo(Runnable llamada) {
        return assertThrows(StatusRuntimeException.class, llamada::run).getStatus().getCode();
    }

    private Participant invitar(Persona quien, String casoId, Persona a, CaseRole rol) {
        InviteParticipantRequest.Builder p = InviteParticipantRequest.newBuilder().setCaseId(casoId)
                .setEmail(a.correo().toUpperCase()).setDisplayName("Invitado " + a.nombre()).setRole(rol)
                .setIdempotencyKey(UUID.randomUUID().toString());
        if (rol == CaseRole.CASE_ROLE_PARTY) {
            p.setSide(PartySide.PARTY_SIDE_CLAIMANT).setCapacity(PartyCapacity.PARTY_CAPACITY_PRINCIPAL);
        }
        return personas(quien).inviteParticipant(p.build()).getParticipant();
    }

    private static PageRequest pagina(int tamano, String token) {
        return PageRequest.newBuilder().setPageSize(tamano).setPageToken(token).build();
    }

    // ------------------------------------------------------------------------- pruebas

    @Test
    void crearDejaAlCreadorComoConciliadorConSuClave() {
        CreateCaseResponse creado = casos(alicia).createCase(nuevo("CC-2026-0001").build());
        LegalCase caso = creado.getLegalCase();
        assertEquals(CaseStatus.CASE_STATUS_OPEN, caso.getStatus());
        assertEquals(1, caso.getRevision());
        assertEquals(1, caso.getParticipantCount());
        assertEquals(ORG.toString(), caso.getOrganizationId());
        assertEquals(detalles("CC-2026-0001"), caso.getDetails());
        assertEquals(CaseRole.CASE_ROLE_CONCILIATOR, creado.getMembership().getRole());
        assertEquals(envuelta("alicia"), creado.getMembership().getWrappedCaseKey());

        var leido = casos(alicia).getCase(GetCaseRequest.newBuilder().setCaseId(caso.getId()).build());
        assertEquals(caso, leido.getLegalCase());
        ListCasesResponse lista = casos(alicia).listCases(ListCasesRequest.newBuilder().setPage(pagina(10, ""))
                .build());
        assertEquals(1, lista.getCasesCount());
        assertEquals(CaseRole.CASE_ROLE_CONCILIATOR, lista.getCases(0).getRole());
    }

    @Test
    void crearEsIdempotenteYElRadicadoEsUnicoPorOrganizacion() {
        CreateCaseRequest peticion = nuevo("CC-2026-0002").build();
        LegalCase primero = casos(alicia).createCase(peticion).getLegalCase();
        assertEquals(primero, casos(alicia).createCase(peticion).getLegalCase(), "reintento con la misma clave");

        assertEquals(Status.Code.ALREADY_EXISTS, codigo(() -> casos(alicia).createCase(nuevo("CC-2026-0002")
                .build())));
        // Otra organizacion puede usar el mismo radicado.
        assertEquals("CC-2026-0002", casos(dario).createCase(nuevo("CC-2026-0002").build()).getLegalCase()
                .getCaseNumber());
        // La misma clave en otra operacion es un error del cliente.
        assertEquals(Status.Code.INVALID_ARGUMENT, codigo(() -> casos(alicia).changeCaseStatus(
                ChangeCaseStatusRequest.newBuilder().setCaseId(primero.getId())
                        .setStatus(CaseStatus.CASE_STATUS_IN_HEARING).setIdempotencyKey(peticion.getIdempotencyKey())
                        .build())));
    }

    @Test
    void quienNoParticipaRecibeNotFoundYLosExternosNoCrean() {
        LegalCase caso = crear(alicia, "CC-2026-0003");
        GetCaseRequest leer = GetCaseRequest.newBuilder().setCaseId(caso.getId()).build();
        assertEquals(Status.Code.NOT_FOUND, codigo(() -> casos(bruno).getCase(leer)));
        assertEquals(Status.Code.NOT_FOUND, codigo(() -> casos(dario).getCase(leer)));
        assertEquals(Status.Code.NOT_FOUND, codigo(() -> casos(bruno).getCase(
                GetCaseRequest.newBuilder().setCaseId(UUID.randomUUID().toString()).build())));
        assertEquals(0, casos(bruno).listCases(ListCasesRequest.newBuilder().setPage(pagina(10, "")).build())
                .getCasesCount());
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> crear(carla, "CC-2026-0099")));
    }

    @Test
    void sinTokenValidoNoHayExpedientes() {
        ListCasesRequest listar = ListCasesRequest.newBuilder().setPage(pagina(10, "")).build();
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> CaseServiceGrpc.newBlockingStub(canal)
                .listCases(listar)));
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> CaseServiceGrpc.newBlockingStub(
                como(token(alicia, -1))).listCases(listar)));
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> CaseServiceGrpc.newBlockingStub(
                como(token(alicia, 600) + "x")).listCases(listar)));
        // Un token firmado con otra clave (otro emisor) tampoco sirve.
        KeyPair ajena = par();
        String falso = token(alicia, 600);
        String[] partes = falso.split("\\.");
        String firmable = partes[0] + "." + partes[1];
        try {
            Signature firma = Signature.getInstance("Ed25519");
            firma.initSign(ajena.getPrivate());
            firma.update(firmable.getBytes(StandardCharsets.US_ASCII));
            String forjado = firmable + "." + TokenAcceso.firma(firma.sign());
            assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> CaseServiceGrpc.newBlockingStub(como(forjado))
                    .listCases(listar)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        // Y sin la firma HMAC del BFF, ni siquiera con un token valido.
        Metadata cabeceras = new Metadata();
        cabeceras.put(Metadatos.AUTORIZACION, "Bearer " + token(alicia, 600));
        Channel sinFirma = ClientInterceptors.intercept(servidor.canalEnMemoria(),
                MetadataUtils.newAttachHeadersInterceptor(cabeceras));
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> CaseServiceGrpc.newBlockingStub(sinFirma)
                .listCases(listar)));
    }

    @Test
    void laInvitacionSeVinculaPorCorreoYLaClaveLaEntregaQuienYaLaTiene() {
        LegalCase caso = crear(alicia, "CC-2026-0004");
        Participant invitado = invitar(alicia, caso.getId(), bruno, CaseRole.CASE_ROLE_ASSISTANT);
        assertEquals(ParticipantState.PARTICIPANT_STATE_INVITED, invitado.getState());
        assertEquals("bruno@prueba.test", invitado.getEmail());
        assertEquals("", invitado.getAccountId());

        // Bruno entra con su cuenta: el caso aparece solo y queda esperando la clave.
        var suyos = casos(bruno).listCases(ListCasesRequest.newBuilder().setPage(pagina(10, "")).build());
        assertEquals(1, suyos.getCasesCount());
        assertEquals(CaseRole.CASE_ROLE_ASSISTANT, suyos.getCases(0).getRole());
        var deBruno = casos(bruno).getCase(GetCaseRequest.newBuilder().setCaseId(caso.getId()).build());
        assertFalse(deBruno.getMembership().hasWrappedCaseKey());
        Participant vinculado = personas(alicia).listParticipants(ListParticipantsRequest.newBuilder()
                .setCaseId(caso.getId()).build()).getParticipantsList().stream()
                .filter(x -> x.getId().equals(invitado.getId())).findFirst().orElseThrow();
        assertEquals(ParticipantState.PARTICIPANT_STATE_AWAITING_KEY, vinculado.getState());
        assertEquals(bruno.cuenta().toString(), vinculado.getAccountId());
        assertEquals("Bruno", vinculado.getDisplayName(), "toma el nombre de la cuenta");

        // Bruno aun no tiene la clave: no puede darla a nadie.
        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> personas(bruno).grantCaseKey(
                GrantCaseKeyRequest.newBuilder().setCaseId(caso.getId()).setParticipantId(invitado.getId())
                        .setWrappedCaseKey(envuelta("bruno")).build())));
        Participant activo = personas(alicia).grantCaseKey(GrantCaseKeyRequest.newBuilder().setCaseId(caso.getId())
                .setParticipantId(invitado.getId()).setWrappedCaseKey(envuelta("bruno")).build()).getParticipant();
        assertEquals(ParticipantState.PARTICIPANT_STATE_ACTIVE, activo.getState());
        assertEquals(envuelta("bruno"), casos(bruno).getCase(GetCaseRequest.newBuilder().setCaseId(caso.getId())
                .build()).getMembership().getWrappedCaseKey());

        // Alguien que aun no ha entrado no puede recibir la clave.
        Participant pendiente = invitar(alicia, caso.getId(), carla, CaseRole.CASE_ROLE_PARTY);
        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> personas(bruno).grantCaseKey(
                GrantCaseKeyRequest.newBuilder().setCaseId(caso.getId()).setParticipantId(pendiente.getId())
                        .setWrappedCaseKey(envuelta("carla")).build())));
        // Repetir la invitacion del mismo correo no crea otra persona.
        assertEquals(Status.Code.ALREADY_EXISTS, codigo(() -> invitar(alicia, caso.getId(), bruno,
                CaseRole.CASE_ROLE_ASSISTANT)));
        // Solo conciliadores invitan.
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> invitar(bruno, caso.getId(), dario,
                CaseRole.CASE_ROLE_ASSISTANT)));
        // Dario es de otra organizacion: aunque lo inviten por correo, su cuenta no se vincula al caso.
        invitar(alicia, caso.getId(), dario, CaseRole.CASE_ROLE_ASSISTANT);
        assertEquals(0, casos(dario).listCases(ListCasesRequest.newBuilder().setPage(pagina(10, "")).build())
                .getCasesCount());
    }

    @Test
    void lasPartesNecesitanLadoYCalidadYLosDemasNo() {
        LegalCase caso = crear(alicia, "CC-2026-0005");
        InviteParticipantRequest.Builder parteIncompleta = InviteParticipantRequest.newBuilder()
                .setCaseId(caso.getId()).setEmail("carla@prueba.test").setDisplayName("Carla")
                .setRole(CaseRole.CASE_ROLE_PARTY).setIdempotencyKey(UUID.randomUUID().toString());
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> personas(alicia).inviteParticipant(parteIncompleta.build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
        assertEquals(List.of(":invite_participant.party_fields"), violaciones(e));

        StatusRuntimeException otro = assertThrows(StatusRuntimeException.class,
                () -> personas(alicia).inviteParticipant(parteIncompleta.setRole(CaseRole.CASE_ROLE_ASSISTANT)
                        .setSide(PartySide.PARTY_SIDE_CLAIMANT).build()));
        assertEquals(List.of(":invite_participant.party_fields"), violaciones(otro));
    }

    @Test
    void laPeticionSeValidaContraElContrato() {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, () -> casos(alicia).createCase(
                CreateCaseRequest.newBuilder().setCaseNumber("cc 1").setKind(CaseKind.CASE_KIND_UNSPECIFIED)
                        .setDetails(detalles("x").toBuilder().setNonce(ByteString.copyFrom(new byte[3])))
                        .setIdempotencyKey("no-es-uuid").build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
        assertEquals(List.of("case_number:string.pattern", "kind:enum.not_in", "details.nonce:bytes.len",
                "wrapped_case_key:required", "idempotency_key:string.uuid"), violaciones(e));
    }

    @Test
    void editarUsaConcurrenciaOptimistaYLasPartesNoEditan() {
        LegalCase caso = crear(alicia, "CC-2026-0006");
        Timestamp audiencia = Timestamp.newBuilder().setSeconds(Instant.now().plusSeconds(86_400).getEpochSecond())
                .build();
        UpdateCaseRequest.Builder editar = UpdateCaseRequest.newBuilder().setCaseId(caso.getId())
                .setExpectedRevision(1).setDetails(detalles("v2")).setScheduledHearingAt(audiencia)
                .setIdempotencyKey(UUID.randomUUID().toString());
        LegalCase v2 = casos(alicia).updateCase(editar.build()).getLegalCase();
        assertEquals(2, v2.getRevision());
        assertEquals(detalles("v2"), v2.getDetails());
        assertEquals(audiencia, v2.getScheduledHearingAt());

        assertEquals(Status.Code.ABORTED, codigo(() -> casos(alicia).updateCase(editar
                .setIdempotencyKey(UUID.randomUUID().toString()).build())), "revision vieja");
        assertEquals(Status.Code.INVALID_ARGUMENT, codigo(() -> casos(alicia).updateCase(UpdateCaseRequest
                .newBuilder().setCaseId(caso.getId()).setExpectedRevision(2)
                .setIdempotencyKey(UUID.randomUUID().toString()).build())), "sin cambios");

        invitar(alicia, caso.getId(), carla, CaseRole.CASE_ROLE_PARTY);
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> casos(carla).updateCase(editar.setExpectedRevision(2)
                .setIdempotencyKey(UUID.randomUUID().toString()).build())));
    }

    @Test
    void elEstadoSigueLasTransicionesYDejaLaLineaDeTiempo() {
        LegalCase caso = crear(alicia, "CC-2026-0007");
        invitar(alicia, caso.getId(), bruno, CaseRole.CASE_ROLE_ASSISTANT);
        Function<CaseStatus, ChangeCaseStatusRequest> a = estado -> ChangeCaseStatusRequest.newBuilder()
                .setCaseId(caso.getId()).setStatus(estado).setIdempotencyKey(UUID.randomUUID().toString()).build();

        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> casos(alicia).changeCaseStatus(
                a.apply(CaseStatus.CASE_STATUS_AGREEMENT))));
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> casos(bruno).changeCaseStatus(
                a.apply(CaseStatus.CASE_STATUS_IN_HEARING))));
        casos(alicia).changeCaseStatus(a.apply(CaseStatus.CASE_STATUS_IN_HEARING));
        casos(alicia).changeCaseStatus(a.apply(CaseStatus.CASE_STATUS_AGREEMENT));
        LegalCase archivado = casos(alicia).changeCaseStatus(a.apply(CaseStatus.CASE_STATUS_ARCHIVED)).getLegalCase();
        assertEquals(CaseStatus.CASE_STATUS_ARCHIVED, archivado.getStatus());

        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> casos(alicia).updateCase(UpdateCaseRequest
                .newBuilder().setCaseId(caso.getId()).setExpectedRevision(archivado.getRevision())
                .setDetails(detalles("tarde")).setIdempotencyKey(UUID.randomUUID().toString()).build())));
        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> invitar(alicia, caso.getId(), carla,
                CaseRole.CASE_ROLE_PARTY)));

        var eventos = casos(bruno).listCaseEvents(ListCaseEventsRequest.newBuilder().setCaseId(caso.getId())
                .setPage(pagina(100, "")).build()).getEventsList();
        List<CaseEventKind> tipos = eventos.stream().map(x -> x.getKind()).toList();
        assertEquals(CaseEventKind.CASE_EVENT_KIND_CREATED, tipos.getLast());
        for (CaseEventKind esperado : List.of(CaseEventKind.CASE_EVENT_KIND_PARTICIPANT_ADDED,
                CaseEventKind.CASE_EVENT_KIND_HEARING_STARTED, CaseEventKind.CASE_EVENT_KIND_HEARING_ENDED)) {
            assertTrue(tipos.contains(esperado), tipos.toString());
        }
        assertEquals(3, tipos.stream().filter(CaseEventKind.CASE_EVENT_KIND_STATUS_CHANGED::equals).count());
        assertEquals("Alicia", eventos.getFirst().getActorDisplayName());
    }

    @Test
    void elCasoConservaUnConciliadorYLasPartesNoCambianDeRol() {
        LegalCase caso = crear(alicia, "CC-2026-0008");
        String yo = casos(alicia).getCase(GetCaseRequest.newBuilder().setCaseId(caso.getId()).build())
                .getMembership().getParticipantId();
        Participant b = invitar(alicia, caso.getId(), bruno, CaseRole.CASE_ROLE_ASSISTANT);
        Participant c = invitar(alicia, caso.getId(), carla, CaseRole.CASE_ROLE_PARTY);
        Function<String, ChangeParticipantRoleRequest.Builder> rol = id -> ChangeParticipantRoleRequest.newBuilder()
                .setCaseId(caso.getId()).setParticipantId(id);

        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> personas(alicia).changeParticipantRole(
                rol.apply(yo).setRole(CaseRole.CASE_ROLE_ASSISTANT).build())), "ultimo conciliador");
        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> personas(alicia).removeParticipant(
                RemoveParticipantRequest.newBuilder().setCaseId(caso.getId()).setParticipantId(yo).build())));
        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> personas(alicia).changeParticipantRole(
                rol.apply(c.getId()).setRole(CaseRole.CASE_ROLE_ASSISTANT).build())), "las partes no cambian");

        personas(alicia).changeParticipantRole(rol.apply(b.getId()).setRole(CaseRole.CASE_ROLE_CONCILIATOR).build());
        assertEquals(CaseRole.CASE_ROLE_ASSISTANT, personas(alicia).changeParticipantRole(
                rol.apply(yo).setRole(CaseRole.CASE_ROLE_ASSISTANT).build()).getParticipant().getRole());
        // Alicia ya no es conciliadora: ahora es Bruno quien retira a Carla.
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> personas(alicia).removeParticipant(
                RemoveParticipantRequest.newBuilder().setCaseId(caso.getId()).setParticipantId(c.getId()).build())));
        personas(bruno).removeParticipant(RemoveParticipantRequest.newBuilder().setCaseId(caso.getId())
                .setParticipantId(c.getId()).build());
        assertEquals(2, personas(bruno).listParticipants(ListParticipantsRequest.newBuilder().setCaseId(caso.getId())
                .build()).getParticipantsCount());
        assertEquals(Status.Code.NOT_FOUND, codigo(() -> casos(carla).getCase(GetCaseRequest.newBuilder()
                .setCaseId(caso.getId()).build())), "la parte retirada ya no ve el caso");
    }

    @Test
    void laListaSePaginaYFiltraPorEstado() {
        for (int i = 1; i <= 3; i++) {
            crear(alicia, "CC-2026-01" + i);
        }
        var primera = casos(alicia).listCases(ListCasesRequest.newBuilder().setPage(pagina(2, "")).build());
        assertEquals(2, primera.getCasesCount());
        assertNotEquals("", primera.getPageInfo().getNextPageToken());
        var segunda = casos(alicia).listCases(ListCasesRequest.newBuilder()
                .setPage(pagina(2, primera.getPageInfo().getNextPageToken())).build());
        assertEquals(1, segunda.getCasesCount());
        assertEquals("", segunda.getPageInfo().getNextPageToken());
        assertEquals(0, casos(alicia).listCases(ListCasesRequest.newBuilder().setPage(pagina(10, ""))
                .setStatus(CaseStatus.CASE_STATUS_ARCHIVED).build()).getCasesCount());
        assertEquals(Status.Code.INVALID_ARGUMENT, codigo(() -> casos(alicia).listCases(ListCasesRequest.newBuilder()
                .setPage(pagina(2, "basura")).build())));
    }

    private static List<String> violaciones(StatusRuntimeException e) {
        try {
            return StatusProto.fromThrowable(e).getDetails(0).unpack(FieldViolations.class).getViolationsList()
                    .stream().map(v -> v.getField() + ":" + v.getRule()).toList();
        } catch (com.google.protobuf.InvalidProtocolBufferException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

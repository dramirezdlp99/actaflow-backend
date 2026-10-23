package com.actaflow.backend.identidad.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.actaflow.backend.identidad.aplicacion.Arranque;
import com.actaflow.backend.identidad.aplicacion.ServicioAutenticacion;
import com.actaflow.backend.identidad.aplicacion.ServicioOrganizacion;
import com.actaflow.backend.identidad.dominio.Base32;
import com.actaflow.backend.identidad.dominio.Criptografia;
import com.actaflow.backend.identidad.dominio.FirmaTokens;
import com.actaflow.backend.identidad.dominio.Totp;
import com.actaflow.backend.identidad.soporte.BaseDatosPrueba;
import com.actaflow.backend.identidad.soporte.Navegador;
import com.actaflow.backend.identidad.soporte.RelojMovil;
import com.actaflow.backend.plataforma.grpc.InterceptorClienteFirma;
import com.actaflow.backend.plataforma.grpc.Metadatos;
import com.actaflow.backend.plataforma.grpc.ServicioPublico;
import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.backend.plataforma.seguridad.TokenAcceso;
import com.actaflow.contracts.common.v1.FieldViolations;
import com.actaflow.contracts.common.v1.PageRequest;
import com.actaflow.contracts.identity.v1.AccountServiceGrpc;
import com.actaflow.contracts.identity.v1.AuthServiceGrpc;
import com.actaflow.contracts.identity.v1.ChangeMemberRoleRequest;
import com.actaflow.contracts.identity.v1.CompleteSecondFactorRequest;
import com.actaflow.contracts.identity.v1.ConfirmTotpEnrollmentRequest;
import com.actaflow.contracts.identity.v1.ConfirmTotpEnrollmentResponse;
import com.actaflow.contracts.identity.v1.ConfirmPresenceRequest;
import com.actaflow.contracts.identity.v1.ConfirmPresenceResponse;
import com.actaflow.contracts.identity.v1.GetCurrentAccountRequest;
import com.actaflow.contracts.identity.v1.GetLoginParametersRequest;
import com.actaflow.contracts.identity.v1.GetPublicKeysRequest;
import com.actaflow.contracts.identity.v1.GetVerificationKeysRequest;
import com.actaflow.contracts.identity.v1.InviteMemberRequest;
import com.actaflow.contracts.identity.v1.InviteMemberResponse;
import com.actaflow.contracts.identity.v1.KeyDirectoryServiceGrpc;
import com.actaflow.contracts.identity.v1.ListMembersRequest;
import com.actaflow.contracts.identity.v1.LoginRequest;
import com.actaflow.contracts.identity.v1.LogoutRequest;
import com.actaflow.contracts.identity.v1.OrganizationServiceGrpc;
import com.actaflow.contracts.identity.v1.PlatformRole;
import com.actaflow.contracts.identity.v1.RefreshSessionRequest;
import com.actaflow.contracts.identity.v1.RegisterRequest;
import com.actaflow.contracts.identity.v1.RegisterResponse;
import com.actaflow.contracts.identity.v1.Session;
import com.actaflow.contracts.identity.v1.SetMemberActiveRequest;
import com.actaflow.contracts.identity.v1.TokenVerificationServiceGrpc;
import com.actaflow.contracts.identity.v1.VerificationKey;
import com.google.protobuf.ByteString;

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
 * Identidad completa por gRPC, con PostgreSQL real: el BFF se simula con los stubs generados
 * del contrato y la firma HMAC del par, como en produccion.
 */
class FlujoIdentidadTest {

    private static final String SECRETO_BFF = "secreto-de-prueba-publico-solo-para-tests-0123456789";
    private static final String TOKEN_ARRANQUE = "invitacion-de-arranque-publica-solo-para-tests-0001";
    private static final String ADMIN = "admin@prueba.test";

    private final RelojMovil reloj = new RelojMovil(Instant.parse("2026-10-15T15:00:00Z"));
    private final Criptografia cripto = new Criptografia("pepper-de-prueba-publico-solo-para-tests-0123456789");
    private final FirmaTokens tokens = new FirmaTokens("token-secret-de-prueba-publico-solo-para-tests-01",
            Duration.ofMinutes(15));
    private Bd bd;
    private ServidorGrpc servidor;
    private Channel canal;

    @BeforeEach
    void iniciar() {
        bd = new Bd(new BaseDatosPrueba().recrear());
        ServicioAutenticacion auth = new ServicioAutenticacion(bd, cripto, tokens, reloj,
                ServicioAutenticacion.Ajustes.porDefecto());
        ServicioOrganizacion org = new ServicioOrganizacion(bd, cripto, reloj);
        InterceptorSesion sesion = new InterceptorSesion(tokens, reloj);
        List<BindableService> servicios = List.of(
                conSesion(new ServicioAuthGrpc(auth), sesion),
                conSesion(new ServicioCuentaGrpc(auth), sesion),
                conSesion(new ServicioOrganizacionGrpc(org), sesion),
                conSesion(new ServicioClavesGrpc.Directorio(org), sesion),
                ServicioPublico.de(new ServicioClavesGrpc.Verificacion(tokens)));
        FirmaHmac firma = new FirmaHmac(SECRETO_BFF, 300, reloj);
        servidor = new ServidorGrpc(servicios, firma, -1, 1 << 20);
        servidor.iniciar();
        canal = ClientInterceptors.intercept(servidor.canalEnMemoria(), new InterceptorClienteFirma(firma));
        new Arranque(bd, cripto, reloj).ejecutar("Centro de Prueba", ADMIN, TOKEN_ARRANQUE);
    }

    @AfterEach
    void apagar() {
        servidor.close();
    }

    private static BindableService conSesion(BindableService servicio, InterceptorSesion sesion) {
        return () -> ServerInterceptors.intercept(servicio, sesion);
    }

    private AuthServiceGrpc.AuthServiceBlockingStub auth() {
        return AuthServiceGrpc.newBlockingStub(canal);
    }

    private Channel conToken(Session sesion) {
        Metadata cabecera = new Metadata();
        cabecera.put(Metadatos.AUTORIZACION, "Bearer " + sesion.getAccessToken());
        return ClientInterceptors.intercept(canal, MetadataUtils.newAttachHeadersInterceptor(cabecera));
    }

    // ---------------------------------------------------------------- ayudas de flujo

    private RegisterResponse registrar(String token, String correo, String contrasena) {
        return auth().register(RegisterRequest.newBuilder()
                .setInvitationToken(token).setEmail(correo).setDisplayName("Persona " + correo)
                .setKdf(Navegador.kdf(correo)).setAuthVerifier(Navegador.verificador(contrasena))
                .setKeyKit(Navegador.kit(correo)).setIdempotencyKey(UUID.nameUUIDFromBytes(
                        ServicioAutenticacion.normalizarCorreo(correo).getBytes()).toString())
                .build());
    }

    private String codigoActual(RegisterResponse registro) {
        byte[] secreto = Base32.decodificar(registro.getTotpEnrollment().getSecretBase32());
        return Totp.codigo(secreto, Totp.paso(reloj.instant()));
    }

    private ConfirmTotpEnrollmentResponse alta(String token, String correo, String contrasena) {
        RegisterResponse registro = registrar(token, correo, contrasena);
        return auth().confirmTotpEnrollment(ConfirmTotpEnrollmentRequest.newBuilder()
                .setEnrollmentId(registro.getTotpEnrollment().getEnrollmentId())
                .setTotpCode(codigoActual(registro)).build());
    }

    private Session ingresar(String correo, String contrasena, String secretoBase32) {
        String desafio = auth().login(LoginRequest.newBuilder().setEmail(correo)
                .setAuthVerifier(Navegador.verificador(contrasena)).build()).getChallengeId();
        reloj.avanzar(Duration.ofSeconds(Totp.PASO_SEGUNDOS));
        String codigo = Totp.codigo(Base32.decodificar(secretoBase32), Totp.paso(reloj.instant()));
        return auth().completeSecondFactor(CompleteSecondFactorRequest.newBuilder().setChallengeId(desafio)
                .setTotpCode(codigo).build()).getSession();
    }

    private static Status.Code codigo(Runnable llamada) {
        return assertThrows(StatusRuntimeException.class, llamada::run).getStatus().getCode();
    }

    // ---------------------------------------------------------------- pruebas

    @Test
    void elPrimerAdministradorSeRegistraConfirmaSuTotpYRecibeSesionYCodigos() {
        RegisterResponse registro = registrar(TOKEN_ARRANQUE, "Admin@Prueba.Test", "clave-del-admin");
        assertEquals(ADMIN, registro.getAccount().getEmail());
        assertEquals(PlatformRole.PLATFORM_ROLE_ADMIN, registro.getAccount().getRole());
        assertTrue(registro.getTotpEnrollment().getOtpauthUri().startsWith("otpauth://totp/ActaFlow:"));

        // Reintento con la misma clave de idempotencia: misma inscripcion, no una cuenta nueva.
        assertEquals(registro.getTotpEnrollment(),
                registrar(TOKEN_ARRANQUE, ADMIN, "clave-del-admin").getTotpEnrollment());

        ConfirmTotpEnrollmentResponse confirmado = auth().confirmTotpEnrollment(
                ConfirmTotpEnrollmentRequest.newBuilder()
                        .setEnrollmentId(registro.getTotpEnrollment().getEnrollmentId())
                        .setTotpCode(codigoActual(registro)).build());
        assertEquals(10, confirmado.getRecoveryCodesCount());
        assertTrue(confirmado.getRecoveryCodes(0).matches("[A-Z0-9]{5}-[A-Z0-9]{5}"));
        Session sesion = confirmado.getSession();
        assertEquals(registro.getAccount().getId(), sesion.getAccount().getId());
        assertEquals("ECDH-P256", sesion.getKeyKit().getPublicKey().getAlgorithm());
        assertEquals(Navegador.kit("Admin@Prueba.Test").getPrivateKeyByPassword(),
                sesion.getKeyKit().getPrivateKeyByPassword());

        var actual = AccountServiceGrpc.newBlockingStub(conToken(sesion))
                .getCurrentAccount(GetCurrentAccountRequest.getDefaultInstance()).getAccount();
        assertEquals(ADMIN, actual.getEmail());
    }

    @Test
    void unaInvitacionUsadaFalsaODeOtroCorreoNoSirve() {
        alta(TOKEN_ARRANQUE, ADMIN, "clave-del-admin");
        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> registrar(TOKEN_ARRANQUE, "otra@prueba.test", "x")));
        assertEquals(Status.Code.FAILED_PRECONDITION,
                codigo(() -> registrar("token-inventado-de-al-menos-32-caracteres!!", "nadie@prueba.test", "x")));
    }

    @Test
    void ingresoEnDosPasosConRefrescoRotativoYDeteccionDeRobo() {
        ConfirmTotpEnrollmentResponse alta = alta(TOKEN_ARRANQUE, ADMIN, "clave-del-admin");
        // Repetir un registro ya confirmado no devuelve de nuevo el secreto TOTP.
        assertEquals(Status.Code.ALREADY_EXISTS, codigo(() -> registrar(TOKEN_ARRANQUE, ADMIN, "clave-del-admin")));

        Session sesion = ingresarConCodigo(alta);
        Session rotada = auth().refreshSession(RefreshSessionRequest.newBuilder()
                .setRefreshToken(sesion.getRefreshToken()).build()).getSession();
        assertNotEquals(sesion.getRefreshToken(), rotada.getRefreshToken());

        // Reusar el token viejo delata un robo: se revoca toda la familia, tambien el rotado.
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> auth().refreshSession(RefreshSessionRequest.newBuilder()
                .setRefreshToken(sesion.getRefreshToken()).build())));
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> auth().refreshSession(RefreshSessionRequest.newBuilder()
                .setRefreshToken(rotada.getRefreshToken()).build())));
    }

    /** Ingresa con un codigo de recuperacion (el secreto TOTP no se vuelve a entregar tras confirmar). */
    private Session ingresarConCodigo(ConfirmTotpEnrollmentResponse alta) {
        String desafio = auth().login(LoginRequest.newBuilder().setEmail(ADMIN)
                .setAuthVerifier(Navegador.verificador("clave-del-admin")).build()).getChallengeId();
        var respuesta = auth().completeSecondFactor(CompleteSecondFactorRequest.newBuilder().setChallengeId(desafio)
                .setRecoveryCode(alta.getRecoveryCodes(0)).build());
        assertEquals(9, respuesta.getRemainingRecoveryCodes());
        return respuesta.getSession();
    }

    @Test
    void elTotpYUnCodigoDeRecuperacionSoloSirvenUnaVez() {
        RegisterResponse registro = registrar(TOKEN_ARRANQUE, ADMIN, "clave-del-admin");
        ConfirmTotpEnrollmentResponse alta = auth().confirmTotpEnrollment(ConfirmTotpEnrollmentRequest.newBuilder()
                .setEnrollmentId(registro.getTotpEnrollment().getEnrollmentId()).setTotpCode(codigoActual(registro))
                .build());
        String secreto = registro.getTotpEnrollment().getSecretBase32();
        Session sesion = ingresar(ADMIN, "clave-del-admin", secreto);
        assertFalse(sesion.getAccessToken().isEmpty());

        // Mismo paso de tiempo: el codigo ya se uso.
        String desafio = auth().login(LoginRequest.newBuilder().setEmail(ADMIN)
                .setAuthVerifier(Navegador.verificador("clave-del-admin")).build()).getChallengeId();
        String repetido = Totp.codigo(Base32.decodificar(secreto), Totp.paso(reloj.instant()));
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> auth().completeSecondFactor(
                CompleteSecondFactorRequest.newBuilder().setChallengeId(desafio).setTotpCode(repetido).build())));

        String recuperacion = alta.getRecoveryCodes(3);
        auth().completeSecondFactor(CompleteSecondFactorRequest.newBuilder().setChallengeId(desafio)
                .setRecoveryCode(recuperacion).build());
        String otro = auth().login(LoginRequest.newBuilder().setEmail(ADMIN)
                .setAuthVerifier(Navegador.verificador("clave-del-admin")).build()).getChallengeId();
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> auth().completeSecondFactor(
                CompleteSecondFactorRequest.newBuilder().setChallengeId(otro).setRecoveryCode(recuperacion).build())));
    }

    @Test
    void cincoFallosBloqueanLaCuentaQuinceMinutosSinRevelarNada() {
        alta(TOKEN_ARRANQUE, ADMIN, "clave-del-admin");
        LoginRequest mala = LoginRequest.newBuilder().setEmail(ADMIN).setAuthVerifier(Navegador.verificador("mala"))
                .build();
        LoginRequest buena = LoginRequest.newBuilder().setEmail(ADMIN)
                .setAuthVerifier(Navegador.verificador("clave-del-admin")).build();
        for (int i = 0; i < 5; i++) {
            StatusRuntimeException e = assertThrows(StatusRuntimeException.class, () -> auth().login(mala));
            assertEquals("Credenciales invalidas", e.getStatus().getDescription());
        }
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> auth().login(buena)));
        reloj.avanzar(Duration.ofMinutes(16));
        assertFalse(auth().login(buena).getChallengeId().isEmpty());
    }

    @Test
    void losParametrosDeIngresoNoRevelanSiUnCorreoExiste() {
        alta(TOKEN_ARRANQUE, ADMIN, "clave-del-admin");
        var real = auth().getLoginParameters(GetLoginParametersRequest.newBuilder().setEmail(ADMIN).build()).getKdf();
        assertEquals(Navegador.kdf(ADMIN), real);
        var falso = auth().getLoginParameters(GetLoginParametersRequest.newBuilder().setEmail("nadie@prueba.test")
                .build()).getKdf();
        assertEquals(16, falso.getSalt().size());
        assertEquals(600_000, falso.getIterations());
        assertEquals(falso, auth().getLoginParameters(GetLoginParametersRequest.newBuilder()
                .setEmail("NADIE@prueba.test").build()).getKdf(), "estable: no delata con sales cambiantes");
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> auth().login(LoginRequest.newBuilder()
                .setEmail("nadie@prueba.test").setAuthVerifier(Navegador.verificador("x")).build())));
    }

    @Test
    void elAdminInvitaElMiembroSeRegistraYLosPermisosSeRespetan() {
        Session admin = alta(TOKEN_ARRANQUE, ADMIN, "clave-del-admin").getSession();
        var orgAdmin = OrganizationServiceGrpc.newBlockingStub(conToken(admin));
        InviteMemberResponse invitacion = orgAdmin.inviteMember(InviteMemberRequest.newBuilder()
                .setEmail("ana@prueba.test").setRole(PlatformRole.PLATFORM_ROLE_MEMBER)
                .setIdempotencyKey(UUID.randomUUID().toString()).build());
        assertTrue(invitacion.getInvitationToken().length() >= 32);

        reloj.avanzar(Duration.ofMinutes(1));
        Session ana = alta(invitacion.getInvitationToken(), "ana@prueba.test", "clave-de-ana").getSession();
        assertEquals(PlatformRole.PLATFORM_ROLE_MEMBER, ana.getAccount().getRole());
        var orgAna = OrganizationServiceGrpc.newBlockingStub(conToken(ana));
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(() -> orgAna.inviteMember(InviteMemberRequest.newBuilder()
                .setEmail("x@prueba.test").setRole(PlatformRole.PLATFORM_ROLE_MEMBER)
                .setIdempotencyKey(UUID.randomUUID().toString()).build())));

        var pagina = orgAna.listMembers(ListMembersRequest.newBuilder()
                .setPage(PageRequest.newBuilder().setPageSize(1)).build());
        assertEquals(1, pagina.getMembersCount());
        assertFalse(pagina.getPageInfo().getNextPageToken().isEmpty());
        var segunda = orgAna.listMembers(ListMembersRequest.newBuilder().setPage(PageRequest.newBuilder()
                .setPageSize(1).setPageToken(pagina.getPageInfo().getNextPageToken())).build());
        assertEquals("ana@prueba.test", segunda.getMembers(0).getAccount().getEmail());
        assertTrue(segunda.getPageInfo().getNextPageToken().isEmpty());

        // El unico administrador no puede quitarse el rol.
        assertEquals(Status.Code.FAILED_PRECONDITION, codigo(() -> orgAdmin.changeMemberRole(
                ChangeMemberRoleRequest.newBuilder().setAccountId(admin.getAccount().getId())
                        .setRole(PlatformRole.PLATFORM_ROLE_MEMBER).build())));

        // Desactivar a Ana cierra sus sesiones.
        orgAdmin.setMemberActive(SetMemberActiveRequest.newBuilder().setAccountId(ana.getAccount().getId())
                .setActive(false).build());
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> auth().refreshSession(RefreshSessionRequest.newBuilder()
                .setRefreshToken(ana.getRefreshToken()).build())));

        var claves = KeyDirectoryServiceGrpc.newBlockingStub(conToken(admin)).getPublicKeys(GetPublicKeysRequest
                .newBuilder().addAccountIds(ana.getAccount().getId()).addAccountIds(UUID.randomUUID().toString())
                .build());
        assertEquals(1, claves.getKeysCount());
        assertEquals(Navegador.kit("ana@prueba.test").getPublicKey().getPublicKey(),
                claves.getKeys(0).getKey().getPublicKey());
    }

    @Test
    void sinTokenConTokenFalsoOVencidoNoHayCuenta() {
        Session sesion = alta(TOKEN_ARRANQUE, ADMIN, "clave-del-admin").getSession();
        var sinToken = AccountServiceGrpc.newBlockingStub(canal);
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> sinToken.getCurrentAccount(
                GetCurrentAccountRequest.getDefaultInstance())));
        Session falsa = sesion.toBuilder().setAccessToken(sesion.getAccessToken() + "x").build();
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> AccountServiceGrpc.newBlockingStub(conToken(falsa))
                .getCurrentAccount(GetCurrentAccountRequest.getDefaultInstance())));
        reloj.avanzar(Duration.ofMinutes(16));
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> AccountServiceGrpc.newBlockingStub(conToken(sesion))
                .getCurrentAccount(GetCurrentAccountRequest.getDefaultInstance())));
        auth().logout(LogoutRequest.newBuilder().setRefreshToken(sesion.getRefreshToken()).build());
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> auth().refreshSession(RefreshSessionRequest.newBuilder()
                .setRefreshToken(sesion.getRefreshToken()).build())));
    }

    @Test
    void lasReglasDelContratoLleganComoDetallesTipados() {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, () -> auth().register(
                RegisterRequest.newBuilder().setInvitationToken("corto").setEmail("no-es-correo")
                        .setAuthVerifier(ByteString.copyFrom(new byte[3])).setIdempotencyKey("x").build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
        FieldViolations detalle;
        try {
            detalle = StatusProto.fromThrowable(e).getDetails(0).unpack(FieldViolations.class);
        } catch (com.google.protobuf.InvalidProtocolBufferException ex) {
            throw new AssertionError(ex);
        }
        List<String> campos = detalle.getViolationsList().stream().map(v -> v.getField() + ":" + v.getRule()).toList();
        assertTrue(campos.contains("email:string.email"), campos.toString());
        assertTrue(campos.contains("invitation_token:string.min_len"), campos.toString());
        assertTrue(campos.contains("auth_verifier:bytes.len"), campos.toString());
        assertTrue(campos.contains("kdf:required"), campos.toString());
        assertTrue(campos.contains("idempotency_key:string.uuid"), campos.toString());
    }

    @Test
    void losOtrosServiciosVerificanTokensConLaClavePublicada() throws Exception {
        Session sesion = alta(TOKEN_ARRANQUE, ADMIN, "clave-del-admin").getSession();
        // Publica, como un JWKS: se puede pedir sin la firma HMAC del BFF (canal en memoria sin firmar).
        VerificationKey clave = TokenVerificationServiceGrpc.newBlockingStub(servidor.canalEnMemoria())
                .getVerificationKeys(GetVerificationKeysRequest.getDefaultInstance()).getKeys(0);
        assertEquals("Ed25519", clave.getAlgorithm());
        var publica = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(clave.getPublicKeySpki().toByteArray()));
        var principal = FirmaTokens.verificar(sesion.getAccessToken(), reloj.instant(), publica, clave.getKeyId())
                .orElseThrow();
        assertEquals(sesion.getAccount().getId(), principal.cuentaId().toString());
        // El token lleva correo y nombre: expedientes los usa para vincular invitaciones a casos.
        var reclamos = TokenAcceso.verificar(sesion.getAccessToken(), reloj.instant(), publica, clave.getKeyId())
                .orElseThrow();
        assertEquals(ADMIN, reclamos.correo());
        assertEquals(sesion.getAccount().getDisplayName(), reclamos.nombre());
        // Los demas servicios siguen exigiendo la firma.
        StatusRuntimeException sinFirma = assertThrows(StatusRuntimeException.class,
                () -> AuthServiceGrpc.newBlockingStub(servidor.canalEnMemoria())
                        .getLoginParameters(GetLoginParametersRequest.newBuilder().setEmail(ADMIN).build()));
        assertEquals(Status.Code.UNAUTHENTICATED, sinFirma.getStatus().getCode());
    }

    @Test
    void laPruebaDePresenciaExigeUnCodigoNuevoYNuncaSirveComoTokenDeAcceso() throws Exception {
        RegisterResponse registro = registrar(TOKEN_ARRANQUE, ADMIN, "clave-del-admin");
        String usado = codigoActual(registro);
        Session sesion = auth().confirmTotpEnrollment(ConfirmTotpEnrollmentRequest.newBuilder()
                .setEnrollmentId(registro.getTotpEnrollment().getEnrollmentId()).setTotpCode(usado).build())
                .getSession();
        byte[] secreto = Base32.decodificar(registro.getTotpEnrollment().getSecretBase32());
        var conSesion = AuthServiceGrpc.newBlockingStub(conToken(sesion));
        String proposito = "accept-minute:" + UUID.randomUUID() + ":" + "a".repeat(64);
        ConfirmPresenceRequest.Builder pedido = ConfirmPresenceRequest.newBuilder().setPurpose(proposito);

        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> auth().confirmPresence(
                pedido.setTotpCode(usado).build())), "sin sesion");
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> conSesion.confirmPresence(
                pedido.setTotpCode(usado).build())), "el codigo ya se uso al confirmar el alta");
        assertEquals(Status.Code.INVALID_ARGUMENT, codigo(() -> conSesion.confirmPresence(
                pedido.setTotpCode(usado).setPurpose("Aceptar Acta").build())));

        reloj.avanzar(Duration.ofSeconds(Totp.PASO_SEGUNDOS));
        String nuevo = Totp.codigo(secreto, Totp.paso(reloj.instant()));
        ConfirmPresenceResponse prueba = conSesion.confirmPresence(pedido.setPurpose(proposito).setTotpCode(nuevo)
                .build());
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> conSesion.confirmPresence(pedido.build())),
                "cada codigo sirve una sola vez");

        VerificationKey clave = TokenVerificationServiceGrpc.newBlockingStub(servidor.canalEnMemoria())
                .getVerificationKeys(GetVerificationKeysRequest.getDefaultInstance()).getKeys(0);
        var publica = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(clave.getPublicKeySpki().toByteArray()));
        var presencia = TokenAcceso.verificarPresencia(prueba.getPresenceToken(), reloj.instant(), publica,
                clave.getKeyId()).orElseThrow();
        assertEquals(sesion.getAccount().getId(), presencia.cuenta().toString());
        assertEquals(proposito, presencia.proposito());
        assertEquals(reloj.instant().plus(FirmaTokens.VIGENCIA_PRESENCIA).getEpochSecond(),
                prueba.getExpiresAt().getSeconds());

        // Nunca intercambiables: la prueba no es token de acceso y el token de acceso no es prueba.
        assertTrue(TokenAcceso.verificar(prueba.getPresenceToken(), reloj.instant(), publica, clave.getKeyId())
                .isEmpty());
        assertTrue(TokenAcceso.verificarPresencia(sesion.getAccessToken(), reloj.instant(), publica,
                clave.getKeyId()).isEmpty());
        Session falsa = Session.newBuilder().setAccessToken(prueba.getPresenceToken()).build();
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(() -> AccountServiceGrpc.newBlockingStub(conToken(falsa))
                .getCurrentAccount(GetCurrentAccountRequest.getDefaultInstance())));

        reloj.avanzar(FirmaTokens.VIGENCIA_PRESENCIA);
        assertTrue(TokenAcceso.verificarPresencia(prueba.getPresenceToken(), reloj.instant(), publica,
                clave.getKeyId()).isEmpty(), "vence a los 2 minutos");
    }

    @Test
    void elArranqueSoloOcurreUnaVez() {
        assertFalse(new Arranque(bd, cripto, reloj).ejecutar("Otro", "otro@prueba.test",
                "otro-token-de-arranque-de-al-menos-32-caracteres"));
    }
}

package com.actaflow.backend.identidad.aplicacion;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import com.actaflow.backend.identidad.aplicacion.Cuentas.Cuenta;
import com.actaflow.backend.identidad.dominio.Base32;
import com.actaflow.backend.identidad.dominio.Criptografia;
import com.actaflow.backend.plataforma.rpc.FalloRpc;
import com.actaflow.backend.plataforma.rpc.FalloRpc.Tipo;
import com.actaflow.backend.identidad.dominio.FirmaTokens;
import com.actaflow.backend.identidad.dominio.Principal;
import com.actaflow.backend.identidad.dominio.Rol;
import com.actaflow.backend.identidad.dominio.Totp;
import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.contracts.identity.v1.Account;
import com.actaflow.contracts.identity.v1.ChangePasswordRequest;
import com.actaflow.contracts.identity.v1.CompleteSecondFactorRequest;
import com.actaflow.contracts.identity.v1.CompleteSecondFactorResponse;
import com.actaflow.contracts.identity.v1.ConfirmTotpEnrollmentResponse;
import com.actaflow.contracts.identity.v1.GetLoginParametersResponse;
import com.actaflow.contracts.identity.v1.KdfParameters;
import com.actaflow.contracts.identity.v1.LoginResponse;
import com.actaflow.contracts.identity.v1.RegisterRequest;
import com.actaflow.contracts.identity.v1.RegisterResponse;
import com.actaflow.contracts.identity.v1.Session;
import com.actaflow.contracts.identity.v1.TotpEnrollment;
import com.google.protobuf.ByteString;

/**
 * Casos de uso de autenticacion. Conocimiento cero: el servidor recibe un verificador derivado de
 * la contrasena en el navegador, nunca la contrasena. El segundo factor (TOTP) es obligatorio.
 */
public final class ServicioAutenticacion {

    /** Parametros de seguridad. */
    public record Ajustes(Duration vigenciaRefresco, Duration vigenciaDesafio, int maxFallos, Duration bloqueo,
                          int iteracionesPorDefecto, String emisorTotp) {

        public static Ajustes porDefecto() {
            return new Ajustes(Duration.ofDays(30), Duration.ofMinutes(5), 5, Duration.ofMinutes(15), 600_000,
                    "ActaFlow");
        }
    }

    static final int CODIGOS_RECUPERACION = 10;
    private static final int INTENTOS_POR_DESAFIO = 5;
    private static final String ALFABETO_CODIGOS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Bd bd;
    private final Criptografia cripto;
    private final FirmaTokens tokens;
    private final Clock reloj;
    private final Ajustes ajustes;

    public ServicioAutenticacion(Bd bd, Criptografia cripto, FirmaTokens tokens, Clock reloj, Ajustes ajustes) {
        this.bd = bd;
        this.cripto = cripto;
        this.tokens = tokens;
        this.reloj = reloj;
        this.ajustes = ajustes;
    }

    public static String normalizarCorreo(String correo) {
        return correo.strip().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- ingreso

    /** Sal e iteraciones de PBKDF2. Responde igual para correos inexistentes (no los revela). */
    public GetLoginParametersResponse parametrosIngreso(String correo) {
        String normalizado = normalizarCorreo(correo);
        Optional<Cuenta> cuenta = bd.transaccion(c -> Cuentas.porCorreo(c, normalizado, false));
        KdfParameters kdf = cuenta.filter(Cuenta::confirmada)
                .map(x -> kdf(x.sal(), x.iteraciones()))
                .orElseGet(() -> kdf(cripto.salFalsa(normalizado), ajustes.iteracionesPorDefecto()));
        return GetLoginParametersResponse.newBuilder().setKdf(kdf).build();
    }

    /** Primer factor: verificador correcto. Devuelve un desafio que espera el codigo TOTP. */
    public LoginResponse ingresar(String correo, byte[] verificador) {
        Instant ahora = reloj.instant();
        byte[] hash = cripto.huella("verifier", verificador);
        Optional<LoginResponse> resultado = bd.transaccion(c -> {
            Optional<Cuenta> encontrada = Cuentas.porCorreo(c, normalizarCorreo(correo), true);
            if (encontrada.isEmpty()) {
                return Optional.empty();
            }
            Cuenta cuenta = encontrada.get();
            boolean bloqueada = cuenta.bloqueadaHasta() != null && cuenta.bloqueadaHasta().isAfter(ahora);
            if (bloqueada || !cuenta.activa() || !cuenta.confirmada()) {
                return Optional.empty();
            }
            if (!Criptografia.igualesConstante(hash, cuenta.hashVerificador())) {
                registrarFallo(c, cuenta, ahora);
                return Optional.empty();
            }
            Bd.ejecutar(c, "update account set failed_logins = 0, locked_until = null where id = ?", cuenta.id());
            UUID desafio = UUID.randomUUID();
            Instant vence = ahora.plus(ajustes.vigenciaDesafio());
            Bd.ejecutar(c, "insert into login_challenge (id, account_id, expires_at) values (?, ?, ?)",
                    desafio, cuenta.id(), vence);
            return Optional.of(LoginResponse.newBuilder().setChallengeId(desafio.toString())
                    .setChallengeExpiresAt(Cuentas.marca(vence)).build());
        });
        return resultado.orElseThrow(FalloRpc::credenciales);
    }

    private void registrarFallo(Connection c, Cuenta cuenta, Instant ahora) throws SQLException {
        if (cuenta.fallos() + 1 >= ajustes.maxFallos()) {
            Bd.ejecutar(c, "update account set failed_logins = 0, locked_until = ? where id = ?",
                    ahora.plus(ajustes.bloqueo()), cuenta.id());
        } else {
            Bd.ejecutar(c, "update account set failed_logins = failed_logins + 1 where id = ?", cuenta.id());
        }
    }

    /** Segundo factor: codigo TOTP o de recuperacion. Emite la sesion. */
    public CompleteSecondFactorResponse segundoFactor(CompleteSecondFactorRequest peticion) {
        Instant ahora = reloj.instant();
        UUID desafio = UUID.fromString(peticion.getChallengeId());
        Optional<CompleteSecondFactorResponse> resultado = bd.transaccion(c -> {
            Optional<UUID> cuentaId = Bd.uno(c, "select account_id from login_challenge where id = ?"
                    + " and used_at is null and expires_at > ? and attempts < ? for update",
                    f -> Bd.uuid(f, "account_id"), desafio, ahora, INTENTOS_POR_DESAFIO);
            if (cuentaId.isEmpty()) {
                return Optional.empty();
            }
            Cuenta cuenta = Cuentas.porId(c, cuentaId.get(), true).orElseThrow();
            boolean valido = cuenta.activa() && switch (peticion.getFactorCase()) {
                case TOTP_CODE -> consumirTotp(c, cuenta, peticion.getTotpCode(), ahora);
                case RECOVERY_CODE -> consumirCodigoRecuperacion(c, cuenta, peticion.getRecoveryCode(), ahora);
                default -> false;
            };
            if (!valido) {
                Bd.ejecutar(c, "update login_challenge set attempts = attempts + 1 where id = ?", desafio);
                return Optional.empty();
            }
            Bd.ejecutar(c, "update login_challenge set used_at = ? where id = ?", ahora, desafio);
            Bd.ejecutar(c, "update account set last_sign_in_at = ? where id = ?", ahora, cuenta.id());
            int restantes = Bd.uno(c,
                    "select count(*) as n from recovery_code where account_id = ? and used_at is null",
                    f -> f.getInt("n"), cuenta.id()).orElse(0);
            return Optional.of(CompleteSecondFactorResponse.newBuilder()
                    .setSession(emitirSesion(c, cuenta, UUID.randomUUID(), ahora))
                    .setRemainingRecoveryCodes(restantes).build());
        });
        return resultado.orElseThrow(FalloRpc::credenciales);
    }

    private boolean consumirTotp(Connection c, Cuenta cuenta, String codigo, Instant ahora) throws SQLException {
        byte[] secreto = cripto.descifrarSecretoTotp(cuenta.totpCifrado());
        OptionalLong paso = Totp.verificar(secreto, codigo, ahora, cuenta.ultimoPasoTotp());
        if (paso.isEmpty()) {
            return false;
        }
        Bd.ejecutar(c, "update account set totp_last_step = ? where id = ?", paso.getAsLong(), cuenta.id());
        return true;
    }

    private boolean consumirCodigoRecuperacion(Connection c, Cuenta cuenta, String codigo, Instant ahora)
            throws SQLException {
        byte[] hash = cripto.huella("recovery", codigo.strip().toUpperCase(Locale.ROOT));
        return Bd.ejecutar(c, "update recovery_code set used_at = ? where account_id = ? and code_hash = ?"
                + " and used_at is null", ahora, cuenta.id(), hash) == 1;
    }

    // ---------------------------------------------------------------- registro

    /** Registro con invitacion. La cuenta queda pendiente hasta confirmar el TOTP. */
    public RegisterResponse registrar(RegisterRequest peticion) {
        Instant ahora = reloj.instant();
        String correo = normalizarCorreo(peticion.getEmail());
        UUID claveRegistro = UUID.fromString(peticion.getIdempotencyKey());
        return bd.transaccion(c -> {
            Optional<Cuenta> repetido = Bd.uno(c, "select " + Cuentas.COLUMNAS
                    + " from account where registration_key = ?", Cuentas::leer, claveRegistro);
            if (repetido.isPresent()) {
                // Reintento del mismo registro (por ejemplo, se cayo la red): misma respuesta.
                Cuenta cuenta = repetido.get();
                if (cuenta.confirmada() || !cuenta.correo().equals(correo)) {
                    throw new FalloRpc(Tipo.YA_EXISTE, "Este registro ya se completo");
                }
                return respuestaRegistro(cuenta);
            }
            record Invitacion(UUID id, UUID organizacionId, String correo, Rol rol) {
            }
            Invitacion invitacion = Bd.uno(c, "select id, organization_id, email, role from invitation"
                    + " where token_hash = ? and used_at is null and expires_at > ? for update",
                    f -> new Invitacion(Bd.uuid(f, "id"), Bd.uuid(f, "organization_id"), f.getString("email"),
                            Rol.valueOf(f.getString("role"))),
                    cripto.huella("invitation", peticion.getInvitationToken()), ahora)
                    .filter(i -> normalizarCorreo(i.correo()).equals(correo))
                    .orElseThrow(() -> new FalloRpc(Tipo.PRECONDICION,
                            "La invitacion no es valida, ya se uso o vencio"));
            if (Cuentas.porCorreo(c, correo, false).isPresent()) {
                throw new FalloRpc(Tipo.YA_EXISTE, "Ya existe una cuenta con ese correo");
            }
            UUID id = UUID.randomUUID();
            byte[] secretoTotp = Criptografia.aleatorio(20);
            var clave = peticion.getKeyKit().getPublicKey();
            Bd.ejecutar(c, "insert into account (id, organization_id, email, display_name, role, kdf_salt,"
                    + " kdf_iterations, verifier_hash, totp_secret_enc, totp_enrollment_id, public_key_id,"
                    + " public_key_algorithm, public_key, public_key_fingerprint, private_key_by_password,"
                    + " private_key_by_recovery, registration_key, created_at)"
                    + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, invitacion.organizacionId(), correo, peticion.getDisplayName().strip(),
                    invitacion.rol().name(), peticion.getKdf().getSalt().toByteArray(),
                    peticion.getKdf().getIterations(),
                    cripto.huella("verifier", peticion.getAuthVerifier().toByteArray()),
                    cripto.cifrarSecretoTotp(secretoTotp), UUID.randomUUID(), clave.getKeyId(), clave.getAlgorithm(),
                    clave.getPublicKey().toByteArray(), Criptografia.sha256Hex(clave.getPublicKey().toByteArray()),
                    peticion.getKeyKit().getPrivateKeyByPassword().toByteArray(),
                    peticion.getKeyKit().getPrivateKeyByRecovery().toByteArray(), claveRegistro, ahora);
            Bd.ejecutar(c, "update invitation set used_at = ? where id = ?", ahora, invitacion.id());
            return respuestaRegistro(Cuentas.porId(c, id, false).orElseThrow());
        });
    }

    private RegisterResponse respuestaRegistro(Cuenta cuenta) {
        byte[] secreto = cripto.descifrarSecretoTotp(cuenta.totpCifrado());
        return RegisterResponse.newBuilder()
                .setAccount(Cuentas.contrato(cuenta))
                .setTotpEnrollment(TotpEnrollment.newBuilder()
                        .setEnrollmentId(cuenta.inscripcionTotp().toString())
                        .setSecretBase32(Base32.codificar(secreto))
                        .setOtpauthUri(Totp.uri(ajustes.emisorTotp(), cuenta.correo(), secreto)))
                .build();
    }

    /** Confirma el TOTP con un codigo de la app: activa la cuenta, entrega los codigos y la sesion. */
    public ConfirmTotpEnrollmentResponse confirmarTotp(String inscripcion, String codigo) {
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> {
            Cuenta cuenta = Bd.uno(c, "select " + Cuentas.COLUMNAS + " from account where totp_enrollment_id = ?"
                    + " for update", Cuentas::leer, UUID.fromString(inscripcion))
                    .orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "Inscripcion no encontrada"));
            if (cuenta.confirmada()) {
                throw new FalloRpc(Tipo.PRECONDICION, "El segundo factor ya estaba confirmado");
            }
            if (!consumirTotp(c, cuenta, codigo, ahora)) {
                throw new FalloRpc(Tipo.NO_AUTENTICADO, "Codigo incorrecto: revisa la hora de tu telefono");
            }
            Bd.ejecutar(c, "update account set totp_confirmed_at = ?, last_sign_in_at = ? where id = ?",
                    ahora, ahora, cuenta.id());
            List<String> codigos = nuevosCodigos(c, cuenta.id());
            Cuenta confirmada = Cuentas.porId(c, cuenta.id(), false).orElseThrow();
            return ConfirmTotpEnrollmentResponse.newBuilder().addAllRecoveryCodes(codigos)
                    .setSession(emitirSesion(c, confirmada, UUID.randomUUID(), ahora)).build();
        });
    }

    private List<String> nuevosCodigos(Connection c, UUID cuentaId) throws SQLException {
        Bd.ejecutar(c, "delete from recovery_code where account_id = ?", cuentaId);
        List<String> codigos = new ArrayList<>();
        while (codigos.size() < CODIGOS_RECUPERACION) {
            String codigo = codigoRecuperacion();
            if (!codigos.contains(codigo)) {
                codigos.add(codigo);
                Bd.ejecutar(c, "insert into recovery_code (account_id, code_hash) values (?, ?)", cuentaId,
                        cripto.huella("recovery", codigo));
            }
        }
        return codigos;
    }

    static String codigoRecuperacion() {
        byte[] azar = Criptografia.aleatorio(10);
        StringBuilder codigo = new StringBuilder(11);
        for (int i = 0; i < azar.length; i++) {
            if (i == 5) {
                codigo.append('-');
            }
            codigo.append(ALFABETO_CODIGOS.charAt((azar[i] & 0xff) % ALFABETO_CODIGOS.length()));
        }
        return codigo.toString();
    }

    // ---------------------------------------------------------------- sesiones

    private Session emitirSesion(Connection c, Cuenta cuenta, UUID familia, Instant ahora) throws SQLException {
        FirmaTokens.TokenEmitido acceso = tokens.emitir(
                new Principal(cuenta.id(), cuenta.organizacionId(), cuenta.rol()), cuenta.correo(), cuenta.nombre(),
                ahora);
        String refresco = Criptografia.tokenAleatorio(32);
        Instant venceRefresco = ahora.plus(ajustes.vigenciaRefresco());
        Bd.ejecutar(c, "insert into refresh_token (id, account_id, family_id, token_hash, expires_at, created_at)"
                + " values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), cuenta.id(), familia,
                cripto.huella("refresh", refresco), venceRefresco, ahora);
        return Session.newBuilder()
                .setAccessToken(acceso.token())
                .setAccessTokenExpiresAt(Cuentas.marca(acceso.venceEn()))
                .setRefreshToken(refresco)
                .setRefreshTokenExpiresAt(Cuentas.marca(venceRefresco))
                .setAccount(Cuentas.contrato(cuenta))
                .setKeyKit(Cuentas.kit(cuenta))
                .build();
    }

    /** Rota el token de refresco. Reusar uno ya rotado revoca toda la familia (posible robo). */
    public Session refrescar(String tokenRefresco) {
        Instant ahora = reloj.instant();
        byte[] hash = cripto.huella("refresh", tokenRefresco);
        record Refresco(UUID id, UUID cuentaId, UUID familia, Instant vence, Instant revocado) {
        }
        Optional<Session> resultado = bd.transaccion(c -> {
            Optional<Refresco> fila = Bd.uno(c, "select id, account_id, family_id, expires_at, revoked_at"
                    + " from refresh_token where token_hash = ? for update",
                    f -> new Refresco(Bd.uuid(f, "id"), Bd.uuid(f, "account_id"), Bd.uuid(f, "family_id"),
                            Bd.instante(f, "expires_at"), Bd.instante(f, "revoked_at")), hash);
            if (fila.isEmpty()) {
                return Optional.empty();
            }
            Refresco r = fila.get();
            if (r.revocado() != null) {
                Bd.ejecutar(c, "update refresh_token set revoked_at = ? where family_id = ? and revoked_at is null",
                        ahora, r.familia());
                return Optional.empty();
            }
            Cuenta cuenta = Cuentas.porId(c, r.cuentaId(), false).orElseThrow();
            if (!r.vence().isAfter(ahora) || !cuenta.activa()) {
                return Optional.empty();
            }
            Bd.ejecutar(c, "update refresh_token set revoked_at = ? where id = ?", ahora, r.id());
            return Optional.of(emitirSesion(c, cuenta, r.familia(), ahora));
        });
        return resultado.orElseThrow(FalloRpc::credenciales);
    }

    /** Cierra la sesion revocando su familia de refresco. Idempotente. */
    public void cerrar(String tokenRefresco) {
        Instant ahora = reloj.instant();
        byte[] hash = cripto.huella("refresh", tokenRefresco);
        bd.transaccion(c -> Bd.ejecutar(c, "update refresh_token set revoked_at = ? where revoked_at is null"
                + " and family_id = (select family_id from refresh_token where token_hash = ?)", ahora, hash));
    }

    // ---------------------------------------------------------------- cuenta

    public Account cuentaActual(Principal principal) {
        return bd.transaccion(c -> Cuentas.porId(c, principal.cuentaId(), false))
                .filter(Cuenta::activa)
                .map(Cuentas::contrato)
                .orElseThrow(FalloRpc::credenciales);
    }

    /** Nuevos codigos de recuperacion; exige un codigo TOTP y la clave privada recifrada con ellos. */
    public List<String> regenerarCodigos(Principal principal, String codigoTotp, byte[] privadaPorRecuperacion) {
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> {
            Cuenta cuenta = Cuentas.porId(c, principal.cuentaId(), true).filter(Cuenta::activa)
                    .orElseThrow(FalloRpc::credenciales);
            if (!consumirTotp(c, cuenta, codigoTotp, ahora)) {
                throw new FalloRpc(Tipo.NO_AUTENTICADO, "Codigo incorrecto");
            }
            Bd.ejecutar(c, "update account set private_key_by_recovery = ? where id = ?", privadaPorRecuperacion,
                    cuenta.id());
            return nuevosCodigos(c, cuenta.id());
        });
    }

    /** Cambia la contrasena (verificador y clave privada recifrada) y cierra todas las sesiones. */
    public void cambiarContrasena(Principal principal, ChangePasswordRequest peticion) {
        Instant ahora = reloj.instant();
        bd.transaccion(c -> {
            Cuenta cuenta = Cuentas.porId(c, principal.cuentaId(), true).filter(Cuenta::activa)
                    .orElseThrow(FalloRpc::credenciales);
            byte[] actual = cripto.huella("verifier", peticion.getCurrentAuthVerifier().toByteArray());
            if (!Criptografia.igualesConstante(actual, cuenta.hashVerificador())
                    || !consumirTotp(c, cuenta, peticion.getTotpCode(), ahora)) {
                throw FalloRpc.credenciales();
            }
            Bd.ejecutar(c, "update account set kdf_salt = ?, kdf_iterations = ?, verifier_hash = ?,"
                    + " private_key_by_password = ? where id = ?", peticion.getNewSalt().toByteArray(),
                    peticion.getNewIterations(), cripto.huella("verifier", peticion.getNewAuthVerifier().toByteArray()),
                    peticion.getPrivateKeyByPassword().toByteArray(), cuenta.id());
            return Bd.ejecutar(c, "update refresh_token set revoked_at = ? where account_id = ? and revoked_at is null",
                    ahora, cuenta.id());
        });
    }

    private static KdfParameters kdf(byte[] sal, int iteraciones) {
        return KdfParameters.newBuilder().setSalt(ByteString.copyFrom(sal)).setIterations(iteraciones).build();
    }
}

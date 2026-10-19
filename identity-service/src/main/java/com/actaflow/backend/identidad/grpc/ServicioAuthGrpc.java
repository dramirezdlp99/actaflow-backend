package com.actaflow.backend.identidad.grpc;

import static com.actaflow.backend.plataforma.rpc.Respuestas.responder;

import com.actaflow.backend.identidad.aplicacion.ServicioAutenticacion;
import com.actaflow.contracts.identity.v1.AuthServiceGrpc;
import com.actaflow.contracts.identity.v1.CompleteSecondFactorRequest;
import com.actaflow.contracts.identity.v1.CompleteSecondFactorResponse;
import com.actaflow.contracts.identity.v1.ConfirmTotpEnrollmentRequest;
import com.actaflow.contracts.identity.v1.ConfirmTotpEnrollmentResponse;
import com.actaflow.contracts.identity.v1.GetLoginParametersRequest;
import com.actaflow.contracts.identity.v1.GetLoginParametersResponse;
import com.actaflow.contracts.identity.v1.LoginRequest;
import com.actaflow.contracts.identity.v1.LoginResponse;
import com.actaflow.contracts.identity.v1.LogoutRequest;
import com.actaflow.contracts.identity.v1.LogoutResponse;
import com.actaflow.contracts.identity.v1.PrivateKeyKit;
import com.actaflow.contracts.identity.v1.RefreshSessionRequest;
import com.actaflow.contracts.identity.v1.RefreshSessionResponse;
import com.actaflow.contracts.identity.v1.RegenerateRecoveryCodesRequest;
import com.actaflow.contracts.identity.v1.RegenerateRecoveryCodesResponse;
import com.actaflow.contracts.identity.v1.RegisterRequest;
import com.actaflow.contracts.identity.v1.RegisterResponse;

import io.grpc.stub.StreamObserver;

/** {@code actaflow.identity.v1.AuthService}: validacion del contrato y delegacion al caso de uso. */
public final class ServicioAuthGrpc extends AuthServiceGrpc.AuthServiceImplBase {

    private final ServicioAutenticacion servicio;

    public ServicioAuthGrpc(ServicioAutenticacion servicio) {
        this.servicio = servicio;
    }

    @Override
    public void getLoginParameters(GetLoginParametersRequest p, StreamObserver<GetLoginParametersResponse> r) {
        responder(r, () -> {
            new Reglas().correo("email", p.getEmail()).verificar();
            return servicio.parametrosIngreso(p.getEmail());
        });
    }

    @Override
    public void register(RegisterRequest p, StreamObserver<RegisterResponse> r) {
        responder(r, () -> {
            PrivateKeyKit kit = p.getKeyKit();
            new Reglas()
                    .largo("invitation_token", p.getInvitationToken(), 32, 128)
                    .correo("email", p.getEmail())
                    .largo("display_name", p.getDisplayName().strip(), 2, 120)
                    .requerido("kdf", p.hasKdf())
                    .bytes("kdf.salt", p.getKdf().getSalt(), 16)
                    .minimo("kdf.iterations", p.getKdf().getIterations(), 600_000)
                    .bytes("auth_verifier", p.getAuthVerifier(), 32)
                    .requerido("key_kit", p.hasKeyKit())
                    .clavePublica("key_kit.public_key", kit.hasPublicKey(), kit.getPublicKey())
                    .blob("key_kit.private_key_by_password", kit.hasPrivateKeyByPassword(),
                            kit.getPrivateKeyByPassword())
                    .blob("key_kit.private_key_by_recovery", kit.hasPrivateKeyByRecovery(),
                            kit.getPrivateKeyByRecovery())
                    .uuid("idempotency_key", p.getIdempotencyKey())
                    .verificar();
            return servicio.registrar(p);
        });
    }

    @Override
    public void confirmTotpEnrollment(ConfirmTotpEnrollmentRequest p,
                                      StreamObserver<ConfirmTotpEnrollmentResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("enrollment_id", p.getEnrollmentId())
                    .patron("totp_code", p.getTotpCode(), Reglas.TOTP).verificar();
            return servicio.confirmarTotp(p.getEnrollmentId(), p.getTotpCode());
        });
    }

    @Override
    public void login(LoginRequest p, StreamObserver<LoginResponse> r) {
        responder(r, () -> {
            new Reglas().correo("email", p.getEmail()).bytes("auth_verifier", p.getAuthVerifier(), 32).verificar();
            return servicio.ingresar(p.getEmail(), p.getAuthVerifier().toByteArray());
        });
    }

    @Override
    public void completeSecondFactor(CompleteSecondFactorRequest p,
                                     StreamObserver<CompleteSecondFactorResponse> r) {
        responder(r, () -> {
            Reglas reglas = new Reglas().uuid("challenge_id", p.getChallengeId());
            switch (p.getFactorCase()) {
                case TOTP_CODE -> reglas.patron("totp_code", p.getTotpCode(), Reglas.TOTP);
                case RECOVERY_CODE -> reglas.patron("recovery_code", p.getRecoveryCode(), Reglas.RECUPERACION);
                default -> reglas.cumple("factor", "required", false, "exactly one field is required in oneof");
            }
            reglas.verificar();
            return servicio.segundoFactor(p);
        });
    }

    @Override
    public void refreshSession(RefreshSessionRequest p, StreamObserver<RefreshSessionResponse> r) {
        responder(r, () -> {
            new Reglas().largo("refresh_token", p.getRefreshToken(), 32, 256).verificar();
            return RefreshSessionResponse.newBuilder().setSession(servicio.refrescar(p.getRefreshToken())).build();
        });
    }

    @Override
    public void logout(LogoutRequest p, StreamObserver<LogoutResponse> r) {
        responder(r, () -> {
            new Reglas().largo("refresh_token", p.getRefreshToken(), 0, 256).verificar();
            if (!p.getRefreshToken().isEmpty()) {
                servicio.cerrar(p.getRefreshToken());
            }
            return LogoutResponse.getDefaultInstance();
        });
    }

    @Override
    public void regenerateRecoveryCodes(RegenerateRecoveryCodesRequest p,
                                        StreamObserver<RegenerateRecoveryCodesResponse> r) {
        responder(r, () -> {
            var principal = Sesion.exigir();
            new Reglas().patron("totp_code", p.getTotpCode(), Reglas.TOTP)
                    .blob("private_key_by_recovery", p.hasPrivateKeyByRecovery(), p.getPrivateKeyByRecovery())
                    .verificar();
            return RegenerateRecoveryCodesResponse.newBuilder().addAllRecoveryCodes(servicio.regenerarCodigos(
                    principal, p.getTotpCode(), p.getPrivateKeyByRecovery().toByteArray())).build();
        });
    }
}

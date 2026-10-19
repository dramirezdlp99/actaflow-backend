package com.actaflow.backend.identidad.grpc;

import static com.actaflow.backend.plataforma.rpc.Respuestas.responder;

import com.actaflow.backend.identidad.aplicacion.ServicioAutenticacion;
import com.actaflow.contracts.identity.v1.AccountServiceGrpc;
import com.actaflow.contracts.identity.v1.ChangePasswordRequest;
import com.actaflow.contracts.identity.v1.ChangePasswordResponse;
import com.actaflow.contracts.identity.v1.GetCurrentAccountRequest;
import com.actaflow.contracts.identity.v1.GetCurrentAccountResponse;

import io.grpc.stub.StreamObserver;

/** {@code actaflow.identity.v1.AccountService}: la cuenta de quien llama. */
public final class ServicioCuentaGrpc extends AccountServiceGrpc.AccountServiceImplBase {

    private final ServicioAutenticacion servicio;

    public ServicioCuentaGrpc(ServicioAutenticacion servicio) {
        this.servicio = servicio;
    }

    @Override
    public void getCurrentAccount(GetCurrentAccountRequest p, StreamObserver<GetCurrentAccountResponse> r) {
        responder(r, () -> GetCurrentAccountResponse.newBuilder()
                .setAccount(servicio.cuentaActual(Sesion.exigir())).build());
    }

    @Override
    public void changePassword(ChangePasswordRequest p, StreamObserver<ChangePasswordResponse> r) {
        responder(r, () -> {
            var principal = Sesion.exigir();
            new Reglas().bytes("current_auth_verifier", p.getCurrentAuthVerifier(), 32)
                    .patron("totp_code", p.getTotpCode(), Reglas.TOTP)
                    .bytes("new_salt", p.getNewSalt(), 16)
                    .minimo("new_iterations", p.getNewIterations(), 600_000)
                    .bytes("new_auth_verifier", p.getNewAuthVerifier(), 32)
                    .blob("private_key_by_password", p.hasPrivateKeyByPassword(), p.getPrivateKeyByPassword())
                    .verificar();
            servicio.cambiarContrasena(principal, p);
            return ChangePasswordResponse.getDefaultInstance();
        });
    }
}

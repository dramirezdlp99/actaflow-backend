package com.actaflow.backend.identidad.grpc;

import static com.actaflow.backend.plataforma.rpc.Respuestas.responder;

import java.util.UUID;

import com.actaflow.backend.identidad.aplicacion.ServicioOrganizacion;
import com.actaflow.backend.identidad.dominio.FirmaTokens;
import com.actaflow.contracts.identity.v1.GetPublicKeysRequest;
import com.actaflow.contracts.identity.v1.GetPublicKeysResponse;
import com.actaflow.contracts.identity.v1.GetVerificationKeysRequest;
import com.actaflow.contracts.identity.v1.GetVerificationKeysResponse;
import com.actaflow.contracts.identity.v1.KeyDirectoryServiceGrpc;
import com.actaflow.contracts.identity.v1.TokenVerificationServiceGrpc;
import com.actaflow.contracts.identity.v1.VerificationKey;
import com.google.protobuf.ByteString;

import io.grpc.stub.StreamObserver;

/** Directorio de claves publicas de usuarios y claves para verificar tokens de acceso. */
public final class ServicioClavesGrpc {

    private ServicioClavesGrpc() {
    }

    /** {@code actaflow.identity.v1.KeyDirectoryService}. */
    public static final class Directorio extends KeyDirectoryServiceGrpc.KeyDirectoryServiceImplBase {

        private final ServicioOrganizacion servicio;

        public Directorio(ServicioOrganizacion servicio) {
            this.servicio = servicio;
        }

        @Override
        public void getPublicKeys(GetPublicKeysRequest p, StreamObserver<GetPublicKeysResponse> r) {
            responder(r, () -> {
                var principal = Sesion.exigir();
                new Reglas().uuidsUnicos("account_ids", p.getAccountIdsList(), 1, 50).verificar();
                return GetPublicKeysResponse.newBuilder().addAllKeys(servicio.clavesPublicas(principal,
                        p.getAccountIdsList().stream().map(UUID::fromString).toList())).build();
            });
        }
    }

    /** {@code actaflow.identity.v1.TokenVerificationService}: lo usan los otros microservicios. */
    public static final class Verificacion extends TokenVerificationServiceGrpc.TokenVerificationServiceImplBase {

        private final FirmaTokens tokens;

        public Verificacion(FirmaTokens tokens) {
            this.tokens = tokens;
        }

        @Override
        public void getVerificationKeys(GetVerificationKeysRequest p, StreamObserver<GetVerificationKeysResponse> r) {
            responder(r, () -> GetVerificationKeysResponse.newBuilder().addKeys(VerificationKey.newBuilder()
                    .setKeyId(tokens.idClave())
                    .setAlgorithm("Ed25519")
                    .setPublicKeySpki(ByteString.copyFrom(tokens.clavePublica().getEncoded()))).build());
        }
    }
}

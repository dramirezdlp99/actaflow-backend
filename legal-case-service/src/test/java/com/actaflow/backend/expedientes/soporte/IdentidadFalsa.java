package com.actaflow.backend.expedientes.soporte;

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

import com.actaflow.backend.plataforma.grpc.ServicioPublico;
import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.backend.plataforma.seguridad.TokenAcceso;
import com.actaflow.contracts.identity.v1.GetVerificationKeysRequest;
import com.actaflow.contracts.identity.v1.GetVerificationKeysResponse;
import com.actaflow.contracts.identity.v1.TokenVerificationServiceGrpc;
import com.actaflow.contracts.identity.v1.VerificationKey;
import com.google.protobuf.ByteString;

import io.grpc.stub.StreamObserver;

/**
 * identity-service de mentira: publica su clave por TokenVerificationService (publico, sin HMAC)
 * en un puerto gRPC nativo libre y firma tokens como los de verdad.
 */
public final class IdentidadFalsa implements AutoCloseable {

    private final KeyPair claves;
    private final String idClave;
    private final ServidorGrpc grpc;

    private IdentidadFalsa() {
        try {
            claves = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        idClave = TokenAcceso.idDeClave(claves.getPublic());
        VerificationKey clave = VerificationKey.newBuilder().setKeyId(idClave).setAlgorithm("Ed25519")
                .setPublicKeySpki(ByteString.copyFrom(claves.getPublic().getEncoded())).build();
        TokenVerificationServiceGrpc.TokenVerificationServiceImplBase servicio =
                new TokenVerificationServiceGrpc.TokenVerificationServiceImplBase() {
                    @Override
                    public void getVerificationKeys(GetVerificationKeysRequest peticion,
                                                    StreamObserver<GetVerificationKeysResponse> respuesta) {
                        respuesta.onNext(GetVerificationKeysResponse.newBuilder().addKeys(clave).build());
                        respuesta.onCompleted();
                    }
                };
        // El secreto no importa: el unico servicio es publico.
        grpc = new ServidorGrpc(List.of(ServicioPublico.de(servicio)),
                new FirmaHmac("secreto-de-identidad-falsa-que-nadie-usa-0123", 300, Clock.systemUTC()), 0, 1 << 16);
        grpc.iniciar();
    }

    public static IdentidadFalsa iniciar() {
        return new IdentidadFalsa();
    }

    public String destinoNativo() {
        return "127.0.0.1:" + grpc.puertoNativo();
    }

    /** Token de acceso valido por 10 minutos, igual que los de identity-service. */
    public String token(UUID cuenta, UUID organizacion, String rol, String correo, String nombre) {
        Map<String, Object> carga = new LinkedHashMap<>();
        carga.put("iss", TokenAcceso.EMISOR);
        carga.put("sub", cuenta.toString());
        carga.put("org", organizacion.toString());
        carga.put("role", rol);
        carga.put("email", correo);
        carga.put("name", nombre);
        carga.put("exp", Instant.now().getEpochSecond() + 600);
        String firmable = TokenAcceso.firmable(idClave, carga);
        try {
            Signature firma = Signature.getInstance("Ed25519");
            firma.initSign(claves.getPrivate());
            firma.update(firmable.getBytes(StandardCharsets.US_ASCII));
            return firmable + "." + TokenAcceso.firma(firma.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        grpc.close();
    }
}

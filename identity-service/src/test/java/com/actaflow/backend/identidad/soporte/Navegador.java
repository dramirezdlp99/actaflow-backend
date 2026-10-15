package com.actaflow.backend.identidad.soporte;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.actaflow.contracts.crypto.v1.CipherSuite;
import com.actaflow.contracts.crypto.v1.EncryptedBlob;
import com.actaflow.contracts.crypto.v1.PublicKey;
import com.actaflow.contracts.identity.v1.KdfParameters;
import com.actaflow.contracts.identity.v1.PrivateKeyKit;
import com.google.protobuf.ByteString;

/**
 * Lo que haria el navegador (simplificado): el verificador sale de la contrasena y la sal; la
 * clave privada viaja cifrada. Aqui basta con bytes deterministas que respeten el contrato.
 */
public final class Navegador {

    private Navegador() {
    }

    public static KdfParameters kdf(String semilla) {
        return KdfParameters.newBuilder().setSalt(ByteString.copyFrom(primeros(semilla + "/sal", 16)))
                .setIterations(600_000).build();
    }

    public static ByteString verificador(String contrasena) {
        return ByteString.copyFrom(primeros(contrasena + "/verificador", 32));
    }

    public static PrivateKeyKit kit(String semilla) {
        byte[] publica = new byte[65];
        publica[0] = 0x04;
        System.arraycopy(primeros(semilla + "/publica", 32), 0, publica, 1, 32);
        System.arraycopy(primeros(semilla + "/publica2", 32), 0, publica, 33, 32);
        return PrivateKeyKit.newBuilder()
                .setPublicKey(PublicKey.newBuilder().setKeyId("clave-" + semilla).setAlgorithm("ECDH-P256")
                        .setPublicKey(ByteString.copyFrom(publica)))
                .setPrivateKeyByPassword(blob(semilla + "/porContrasena"))
                .setPrivateKeyByRecovery(blob(semilla + "/porRecuperacion"))
                .build();
    }

    public static EncryptedBlob blob(String semilla) {
        return EncryptedBlob.newBuilder().setSuite(CipherSuite.CIPHER_SUITE_AES_256_GCM).setKeyId("kek")
                .setNonce(ByteString.copyFrom(primeros(semilla + "/nonce", 12)))
                .setCiphertext(ByteString.copyFrom(primeros(semilla + "/cifrado", 32))).build();
    }

    private static byte[] primeros(String texto, int n) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
            byte[] resultado = new byte[n];
            System.arraycopy(hash, 0, resultado, 0, n);
            return resultado;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

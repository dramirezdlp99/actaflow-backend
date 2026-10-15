package com.actaflow.backend.identidad.dominio;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Primitivas criptograficas del servicio de identidad, con un "pepper" del servidor.
 *
 * <p>El pepper (ACTAFLOW_IDENTITY_PEPPER) nunca se guarda en la base de datos: si alguien roba
 * la base, los hashes de verificadores, tokens y codigos no le sirven sin el pepper.
 */
public final class Criptografia {

    private static final SecureRandom AZAR = new SecureRandom();
    private static final int NONCE_GCM = 12;
    private static final int ETIQUETA_GCM_BITS = 128;

    private final byte[] pepper;
    private final SecretKeySpec claveTotp;

    public Criptografia(String pepper) {
        if (pepper == null || pepper.length() < 32) {
            throw new IllegalArgumentException("ACTAFLOW_IDENTITY_PEPPER debe tener al menos 32 caracteres");
        }
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
        // Clave AES derivada del pepper solo para cifrar los secretos TOTP guardados.
        this.claveTotp = new SecretKeySpec(hmac("actaflow/totp-at-rest".getBytes(StandardCharsets.UTF_8)), "AES");
    }

    public static byte[] aleatorio(int bytes) {
        byte[] resultado = new byte[bytes];
        AZAR.nextBytes(resultado);
        return resultado;
    }

    /** Texto aleatorio seguro para URL (base64url sin relleno). */
    public static String tokenAleatorio(int bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(aleatorio(bytes));
    }

    public static String sha256Hex(byte[] datos) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public static boolean igualesConstante(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean igualesConstante(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    static String codificarUrl(String texto) {
        return URLEncoder.encode(texto, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** HMAC-SHA256 con el pepper. */
    public byte[] hmac(byte[] datos) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(datos);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }

    /** Hash con dominio separado: el mismo dato en usos distintos da hashes distintos. */
    public byte[] huella(String uso, byte[] dato) {
        byte[] prefijo = (uso + "\n").getBytes(StandardCharsets.UTF_8);
        return hmac(ByteBuffer.allocate(prefijo.length + dato.length).put(prefijo).put(dato).array());
    }

    public byte[] huella(String uso, String dato) {
        return huella(uso, dato.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Sal falsa pero estable para un correo que no existe: GetLoginParameters responde igual para
     * cuentas reales e inexistentes, asi nadie puede averiguar que correos estan registrados.
     */
    public byte[] salFalsa(String correoNormalizado) {
        return Arrays.copyOf(huella("login-salt", correoNormalizado), 16);
    }

    public byte[] cifrarSecretoTotp(byte[] secreto) {
        try {
            byte[] nonce = aleatorio(NONCE_GCM);
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, claveTotp, new GCMParameterSpec(ETIQUETA_GCM_BITS, nonce));
            byte[] cifrado = aes.doFinal(secreto);
            return ByteBuffer.allocate(NONCE_GCM + cifrado.length).put(nonce).put(cifrado).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar el secreto TOTP", e);
        }
    }

    public byte[] descifrarSecretoTotp(byte[] guardado) {
        try {
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, claveTotp, new GCMParameterSpec(ETIQUETA_GCM_BITS, guardado, 0, NONCE_GCM));
            return aes.doFinal(guardado, NONCE_GCM, guardado.length - NONCE_GCM);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo descifrar el secreto TOTP: pepper distinto?", e);
        }
    }
}

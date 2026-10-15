package com.actaflow.backend.identidad.dominio;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Codigos de un solo uso basados en tiempo (RFC 6238): HMAC-SHA1, 6 digitos, pasos de 30 s.
 * Es lo que usan Google Authenticator, Microsoft Authenticator, Aegis y similares.
 */
public final class Totp {

    public static final int PASO_SEGUNDOS = 30;
    private static final int DIGITOS = 6;
    /** Tolerancia: un paso antes y uno despues, por relojes un poco desfasados. */
    private static final int VENTANA = 1;

    private Totp() {
    }

    public static long paso(Instant instante) {
        return instante.getEpochSecond() / PASO_SEGUNDOS;
    }

    public static String codigo(byte[] secreto, long paso) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secreto, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(paso).array());
            int desplazamiento = h[h.length - 1] & 0x0f;
            int binario = ((h[desplazamiento] & 0x7f) << 24) | ((h[desplazamiento + 1] & 0xff) << 16)
                    | ((h[desplazamiento + 2] & 0xff) << 8) | (h[desplazamiento + 3] & 0xff);
            return String.format("%06d", binario % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 no disponible", e);
        }
    }

    /**
     * Verifica un codigo y devuelve el paso que coincidio. Rechaza pasos ya usados
     * ({@code pasoUsado} o anteriores), asi un codigo interceptado no se puede reutilizar.
     */
    public static OptionalLong verificar(byte[] secreto, String codigo, Instant ahora, long pasoUsado) {
        if (codigo == null || codigo.length() != DIGITOS) {
            return OptionalLong.empty();
        }
        long actual = paso(ahora);
        for (long p = actual - VENTANA; p <= actual + VENTANA; p++) {
            if (p > pasoUsado && Criptografia.igualesConstante(codigo(secreto, p), codigo)) {
                return OptionalLong.of(p);
            }
        }
        return OptionalLong.empty();
    }

    /** URI que las apps de autenticacion leen desde un codigo QR. */
    public static String uri(String emisor, String cuenta, byte[] secreto) {
        return "otpauth://totp/" + Criptografia.codificarUrl(emisor) + ":" + Criptografia.codificarUrl(cuenta)
                + "?secret=" + Base32.codificar(secreto) + "&issuer=" + Criptografia.codificarUrl(emisor)
                + "&algorithm=SHA1&digits=" + DIGITOS + "&period=" + PASO_SEGUNDOS;
    }
}

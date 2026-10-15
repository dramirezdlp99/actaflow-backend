package com.actaflow.backend.identidad.dominio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TotpTest {

    /** Secreto de los vectores de prueba del RFC 6238 (apendice B, SHA-1). */
    private static final byte[] RFC = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void coincideConLosVectoresDelRfc6238() {
        // El RFC da 8 digitos; con 6 son los ultimos 6.
        assertEquals("287082", Totp.codigo(RFC, Totp.paso(Instant.ofEpochSecond(59))));
        assertEquals("081804", Totp.codigo(RFC, Totp.paso(Instant.ofEpochSecond(1_111_111_109))));
        assertEquals("050471", Totp.codigo(RFC, Totp.paso(Instant.ofEpochSecond(1_111_111_111))));
        assertEquals("005924", Totp.codigo(RFC, Totp.paso(Instant.ofEpochSecond(1_234_567_890))));
        assertEquals("279037", Totp.codigo(RFC, Totp.paso(Instant.ofEpochSecond(2_000_000_000))));
    }

    @Test
    void aceptaUnPasoDeDesfaseYRechazaCodigosReusados() {
        Instant ahora = Instant.ofEpochSecond(1_760_000_000L);
        long paso = Totp.paso(ahora);
        String anterior = Totp.codigo(RFC, paso - 1);
        assertEquals(paso - 1, Totp.verificar(RFC, anterior, ahora, 0).getAsLong());
        assertTrue(Totp.verificar(RFC, anterior, ahora, paso - 1).isEmpty(), "reuso");
        assertTrue(Totp.verificar(RFC, Totp.codigo(RFC, paso - 2), ahora, 0).isEmpty(), "demasiado viejo");
        assertTrue(Totp.verificar(RFC, "12345", ahora, 0).isEmpty());
    }

    @Test
    void base32IdaYVuelta() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Base32.codificar(RFC));
        assertArrayEquals(RFC, Base32.decodificar("gezd gnbv gy3t qojq gezd gnbv gy3t qojq"));
    }

    @Test
    void laUriLaEntiendenLasAppsDeAutenticacion() {
        String uri = Totp.uri("ActaFlow", "ana@prueba.test", RFC);
        assertEquals("otpauth://totp/ActaFlow:ana%40prueba.test?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                + "&issuer=ActaFlow&algorithm=SHA1&digits=6&period=30", uri);
    }
}

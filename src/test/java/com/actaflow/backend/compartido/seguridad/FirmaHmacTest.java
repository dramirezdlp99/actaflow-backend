package com.actaflow.backend.compartido.seguridad;

import static com.actaflow.backend.soporte.DatosPrueba.SECRETO_BFF;
import static com.actaflow.backend.soporte.DatosPrueba.TS_VECTORES;
import static com.actaflow.backend.soporte.DatosPrueba.relojFijo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FirmaHmacTest {

    private static final String TS = Long.toString(TS_VECTORES);
    private static final byte[] VACIO = new byte[0];

    private final FirmaHmac firma = new FirmaHmac(SECRETO_BFF, 300, relojFijo(TS_VECTORES));

    @Test
    void coincideConLosVectoresCompartidosConElBffYElMotor() {
        assertEquals("fcd686a749c71724bde4a22b49a4f0efb6804679c254cb1666ddbf112c023f49",
                firma.firmar(TS, "GET", "/api/v1/status", VACIO));
        assertEquals("79b879931374cba8459f058a45e64a75ca976a02f3c9d5fa1fc2764db1e5e7dc",
                firma.firmar(TS, "POST", "/v1/diagnostico/benchmark",
                        "{\"modelos\":[\"tiny\"]}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("4db3ff880b724cc566f52626e2f1315b187eae83d8ef69b271079ad3df3eee7c",
                firma.firmar(TS, "GET", "/health", VACIO));
    }

    @Test
    void elMensajeCanonicoNormalizaElMetodoYUsaElHashDelCuerpo() {
        assertEquals(TS + "\nGET\n/x\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                FirmaHmac.mensajeCanonico(TS, "get", "/x", VACIO));
    }

    @Test
    void aceptaUnaFirmaValidaAunqueVengaEnMayusculas() {
        String valida = firma.firmar(TS, "POST", "/r", "{}".getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> firma.verificar(TS, valida.toUpperCase(), "POST", "/r",
                "{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rechazaSiFaltaAlgunaCabecera() {
        assertMotivo(FirmaInvalidaException.Motivo.FALTA_CABECERA, null, "abc");
        assertMotivo(FirmaInvalidaException.Motivo.FALTA_CABECERA, TS, null);
        assertMotivo(FirmaInvalidaException.Motivo.FALTA_CABECERA, " ", "abc");
    }

    @Test
    void rechazaTimestampsNoNumericosOAbsurdos() {
        for (String malo : new String[] {"abc", "-5", "1.5", "9999999999999"}) {
            assertMotivo(FirmaInvalidaException.Motivo.TIMESTAMP_INVALIDO, malo, "abc");
        }
    }

    @Test
    void rechazaFirmasFueraDeLaVentanaYAceptaElBorde() {
        String valida = firma.firmar(TS, "GET", "/r", VACIO);
        for (long desfase : new long[] {301, -301, 3600}) {
            FirmaHmac otraHora = new FirmaHmac(SECRETO_BFF, 300, relojFijo(TS_VECTORES + desfase));
            FirmaInvalidaException e = assertThrows(FirmaInvalidaException.class,
                    () -> otraHora.verificar(TS, valida, "GET", "/r", VACIO));
            assertEquals(FirmaInvalidaException.Motivo.FUERA_DE_VENTANA, e.motivo());
        }
        FirmaHmac enElBorde = new FirmaHmac(SECRETO_BFF, 300, relojFijo(TS_VECTORES + 300));
        assertDoesNotThrow(() -> enElBorde.verificar(TS, valida, "GET", "/r", VACIO));
    }

    @Test
    void rechazaSiSeAlteraMetodoRutaOCuerpo() {
        byte[] original = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String valida = firma.firmar(TS, "POST", "/r", original);
        assertFirmaIncorrecta(valida, "POST", "/r", "{\"a\":2}".getBytes(StandardCharsets.UTF_8));
        assertFirmaIncorrecta(valida, "POST", "/otra", original);
        assertFirmaIncorrecta(valida, "PUT", "/r", original);
        assertFirmaIncorrecta(valida, "POST", "/r?x=1", original);
    }

    @Test
    void rechazaUnaFirmaHechaConOtroSecreto() {
        FirmaHmac otra = new FirmaHmac("otro-secreto-distinto-de-al-menos-32-caracteres", 300,
                relojFijo(TS_VECTORES));
        assertFirmaIncorrecta(otra.firmar(TS, "GET", "/r", VACIO), "GET", "/r", VACIO);
    }

    @Test
    void lasCabecerasGeneradasSonVerificables() {
        Map<String, String> cabeceras = firma.cabeceras("GET", "/health", VACIO);
        assertEquals(TS, cabeceras.get(FirmaHmac.CABECERA_TIMESTAMP));
        assertDoesNotThrow(() -> firma.verificar(cabeceras.get(FirmaHmac.CABECERA_TIMESTAMP),
                cabeceras.get(FirmaHmac.CABECERA_FIRMA), "GET", "/health", VACIO));
    }

    private void assertMotivo(FirmaInvalidaException.Motivo esperado, String ts, String valor) {
        FirmaInvalidaException e = assertThrows(FirmaInvalidaException.class,
                () -> firma.verificar(ts, valor, "GET", "/r", VACIO));
        assertEquals(esperado, e.motivo());
    }

    private void assertFirmaIncorrecta(String valor, String metodo, String ruta, byte[] cuerpo) {
        FirmaInvalidaException e = assertThrows(FirmaInvalidaException.class,
                () -> firma.verificar(TS, valor, metodo, ruta, cuerpo));
        assertEquals(FirmaInvalidaException.Motivo.FIRMA_INCORRECTA, e.motivo());
    }
}

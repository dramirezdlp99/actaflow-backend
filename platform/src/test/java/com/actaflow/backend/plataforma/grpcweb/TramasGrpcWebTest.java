package com.actaflow.backend.plataforma.grpcweb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.actaflow.backend.plataforma.grpcweb.TramasGrpcWeb.Trama;
import com.actaflow.backend.plataforma.grpcweb.TramasGrpcWeb.TramaInvalidaException;

import io.grpc.Metadata;

import org.junit.jupiter.api.Test;

class TramasGrpcWebTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void unaTramaDeDatosTieneBanderaCeroYLongitudBigEndian() {
        assertEquals("00000000030a0101", HEX.formatHex(TramasGrpcWeb.datos(HEX.parseHex("0a0101"))));
        assertEquals("0000000000", HEX.formatHex(TramasGrpcWeb.datos(new byte[0])));
    }

    @Test
    void decodificaDatosYTrailers() throws TramaInvalidaException {
        byte[] cuerpo = concatenar(TramasGrpcWeb.datos("abc".getBytes()), TramasGrpcWeb.trailers(0, "", null));
        List<Trama> tramas = TramasGrpcWeb.decodificar(cuerpo, 100);
        assertEquals(2, tramas.size());
        assertArrayEquals("abc".getBytes(), tramas.get(0).contenido());
        assertFalse(tramas.get(0).esTrailers());
        assertTrue(tramas.get(1).esTrailers());
        assertEquals(Map.of("grpc-status", "0"), TramasGrpcWeb.leerTrailers(tramas.get(1).contenido()));
    }

    @Test
    void rechazaCuerposMalformados() {
        assertInvalida("000000", "incompleta");
        assertInvalida("0000000005616263", "truncada");
        assertInvalida("010000000161", "Compresion");
        assertInvalida("020000000161", "Bandera desconocida");
        assertInvalida("0000001000", "supera el maximo");
    }

    @Test
    void elMensajeDeErrorViajaCodificadoYSeRecuperaIgual() throws TramaInvalidaException {
        String mensaje = "Llamada no autorizada: ñandú 100%";
        byte[] trama = TramasGrpcWeb.trailers(16, mensaje, null);
        String texto = new String(TramasGrpcWeb.decodificar(trama, 1000).getFirst().contenido());
        assertTrue(texto.startsWith("grpc-status:16\r\ngrpc-message:"), texto);
        assertTrue(texto.chars().allMatch(c -> c < 0x80), "solo ASCII en el cable");
        assertEquals(mensaje, TramasGrpcWeb.leerTrailers(TramasGrpcWeb.decodificar(trama, 1000).getFirst()
                .contenido()).get("grpc-message"));
    }

    @Test
    void entiendeElMensajeCodificadoPorElMotorEnPython() {
        // urllib.parse.quote("Llamada no autorizada: ñ", safe=" ")
        assertEquals("Llamada no autorizada: ñ",
                TramasGrpcWeb.decodificarPorcentaje("Llamada no autorizada%3A %C3%B1"));
    }

    @Test
    void losMetadatosBinariosViajanEnBase64SinRelleno() throws TramaInvalidaException {
        Metadata extra = new Metadata();
        extra.put(Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER), new byte[] {1, 2, -1});
        extra.put(Metadata.Key.of("x-extra", Metadata.ASCII_STRING_MARSHALLER), "1");
        byte[] trama = TramasGrpcWeb.trailers(3, "x", extra);
        Map<String, String> leidos = TramasGrpcWeb.leerTrailers(TramasGrpcWeb.decodificar(trama, 1000).getFirst()
                .contenido());
        assertEquals("AQL/", leidos.get("grpc-status-details-bin"));
        assertArrayEquals(new byte[] {1, 2, -1}, Base64.getDecoder().decode(leidos.get("grpc-status-details-bin")));
        assertEquals("1", leidos.get("x-extra"));
    }

    private static void assertInvalida(String hex, String fragmento) {
        TramaInvalidaException e = assertThrows(TramaInvalidaException.class,
                () -> TramasGrpcWeb.decodificar(HEX.parseHex(hex), 8));
        assertTrue(e.getMessage().contains(fragmento), e.getMessage());
    }

    private static byte[] concatenar(byte[] a, byte[] b) {
        byte[] resultado = new byte[a.length + b.length];
        System.arraycopy(a, 0, resultado, 0, a.length);
        System.arraycopy(b, 0, resultado, a.length, b.length);
        return resultado;
    }
}

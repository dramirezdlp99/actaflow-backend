package com.actaflow.backend.plataforma.grpcweb;

import static com.actaflow.backend.plataforma.soporte.ServiciosFalsos.SECRETO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.actaflow.backend.plataforma.grpc.Metadatos;
import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.backend.plataforma.grpcweb.TramasGrpcWeb.Trama;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.backend.plataforma.soporte.ServiciosFalsos;
import com.actaflow.contracts.common.v1.FieldViolations;
import com.actaflow.contracts.jobs.v1.GetJobRequest;
import com.actaflow.contracts.jobs.v1.WatchJobRequest;
import com.actaflow.contracts.jobs.v1.WatchJobResponse;
import com.actaflow.contracts.system.v1.GetServiceStatusResponse;
import com.actaflow.contracts.system.v1.Transport;

import io.grpc.Metadata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasarelaGrpcWebTest {

    private static final String ESTADO = "/actaflow.system.v1.ServiceStatusService/GetServiceStatus";
    private final FirmaHmac firma = new FirmaHmac(SECRETO, 300, Clock.systemUTC());
    private ServidorGrpc servidor;
    private PasarelaGrpcWeb pasarela;

    @BeforeEach
    void iniciar() {
        servidor = new ServidorGrpc(List.of(new ServiciosFalsos.Estado(), new ServiciosFalsos.Trabajos(),
                new ServiciosFalsos.TranscripcionEnVivo()), firma, -1, 4096);
        servidor.iniciar();
        pasarela = new PasarelaGrpcWeb(servidor.canalEnMemoria(), servidor.tiposDeMetodo(), 4096,
                Duration.ofSeconds(30));
    }

    @AfterEach
    void apagar() {
        servidor.close();
    }

    /** Respuesta HTTP capturada en memoria. */
    private static final class Respuesta implements PasarelaGrpcWeb.Salida {
        int estado;
        String tipo;
        final ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();

        @Override
        public void comenzar(int estadoHttp, String tipoContenido) {
            estado = estadoHttp;
            tipo = tipoContenido;
        }

        @Override
        public OutputStream cuerpo() {
            return cuerpo;
        }

        List<Trama> tramas() throws Exception {
            return TramasGrpcWeb.decodificar(cuerpo.toByteArray(), 1 << 20);
        }

        List<byte[]> mensajes() throws Exception {
            return tramas().stream().filter(t -> !t.esTrailers()).map(Trama::contenido).toList();
        }

        Map<String, String> trailers() throws Exception {
            return TramasGrpcWeb.leerTrailers(tramas().stream().filter(Trama::esTrailers).findFirst().orElseThrow()
                    .contenido());
        }
    }

    private Respuesta llamar(String ruta, byte[] mensaje, boolean firmar) throws Exception {
        Map<String, String> cabeceras = new HashMap<>();
        if (firmar) {
            Metadata m = firma.metadatos(ruta, mensaje);
            cabeceras.put("x-actaflow-timestamp", m.get(Metadatos.TIMESTAMP));
            cabeceras.put("x-actaflow-signature", m.get(Metadatos.FIRMA));
        }
        return enviar(ruta, TramasGrpcWeb.TIPO, cabeceras, TramasGrpcWeb.datos(mensaje));
    }

    private Respuesta enviar(String ruta, String tipo, Map<String, String> cabeceras, byte[] cuerpo) throws Exception {
        Respuesta respuesta = new Respuesta();
        pasarela.atender(ruta, tipo, cabeceras, new ByteArrayInputStream(cuerpo), respuesta);
        return respuesta;
    }

    @Test
    void llamadaUnariaFirmadaDevuelveElMensajeYEstadoCero() throws Exception {
        Respuesta r = llamar(ESTADO, new byte[0], true);
        assertEquals(200, r.estado);
        assertEquals("application/grpc-web+proto", r.tipo);
        GetServiceStatusResponse respuesta = GetServiceStatusResponse.parseFrom(r.mensajes().getFirst());
        assertEquals(Transport.TRANSPORT_GRPC_WEB, respuesta.getStatus().getServedVia());
        assertEquals("0", r.trailers().get("grpc-status"));
    }

    @Test
    void sinFirmaElErrorViajaEnLosTrailersConHttp200() throws Exception {
        Respuesta r = llamar(ESTADO, new byte[0], false);
        assertEquals(200, r.estado);
        assertTrue(r.mensajes().isEmpty());
        assertEquals(Map.of("grpc-status", "16", "grpc-message", "Llamada no autorizada"), r.trailers());
    }

    @Test
    void losDetallesTipadosDelErrorLleganEnGrpcStatusDetailsBin() throws Exception {
        Respuesta r = llamar("/actaflow.jobs.v1.JobService/GetJob",
                GetJobRequest.newBuilder().setJobId("x").build().toByteArray(), true);
        Map<String, String> trailers = r.trailers();
        assertEquals("3", trailers.get("grpc-status"));
        assertEquals("La peticion incumple el contrato: ñandú 100%", trailers.get("grpc-message"));
        String detalles = trailers.get("grpc-status-details-bin");
        assertNotNull(detalles);
        com.google.rpc.Status estado = com.google.rpc.Status.parseFrom(Base64.getDecoder().decode(detalles));
        assertEquals("string.uuid", estado.getDetails(0).unpack(FieldViolations.class).getViolations(0).getRule());
    }

    @Test
    void streamingDelServidorEnviaUnaTramaPorMensaje() throws Exception {
        Respuesta r = llamar("/actaflow.jobs.v1.JobService/WatchJob",
                WatchJobRequest.newBuilder().setJobId("t-1").build().toByteArray(), true);
        assertEquals(3, r.mensajes().size());
        assertEquals("t-1", WatchJobResponse.parseFrom(r.mensajes().get(2)).getJob().getId());
        assertEquals("0", r.trailers().get("grpc-status"));
    }

    @Test
    void metodosDesconocidosInternosODeStreamingDelClienteSonUnimplemented() throws Exception {
        for (String ruta : List.of("/actaflow.system.v1.NoExisteService/Metodo",
                "/grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo",
                "/actaflow.engine.v1.TranscriptionService/StreamTranscription",
                "/actaflow.system.v1.ServiceStatusService/../x")) {
            assertEquals("12", llamar(ruta, new byte[0], true).trailers().get("grpc-status"), ruta);
        }
    }

    @Test
    void tramasMalformadasOVariosMensajesSonArgumentoInvalido() throws Exception {
        assertEquals("3", enviar(ESTADO, TramasGrpcWeb.TIPO, Map.of(), new byte[] {0, 0, 0}).trailers()
                .get("grpc-status"));
        byte[] dos = new byte[10];
        System.arraycopy(TramasGrpcWeb.datos(new byte[0]), 0, dos, 0, 5);
        System.arraycopy(TramasGrpcWeb.datos(new byte[0]), 0, dos, 5, 5);
        assertEquals("3", enviar(ESTADO, TramasGrpcWeb.TIPO, Map.of(), dos).trailers().get("grpc-status"));
    }

    @Test
    void otroTipoDeContenidoEs415YUnCuerpoEnormeEs413() throws Exception {
        assertEquals(415, enviar(ESTADO, "application/json", Map.of(), "{}".getBytes()).estado);
        assertEquals(413, enviar(ESTADO, TramasGrpcWeb.TIPO, Map.of(), TramasGrpcWeb.datos(new byte[5000])).estado);
    }

    @Test
    void soloReenviaFirmaYSesionYElTransporteLoPoneLaPasarela() {
        Metadata m = PasarelaGrpcWeb.metadatos(Map.of("x-actaflow-timestamp", "1", "authorization", "Bearer t",
                "cookie", "secreta", "x-actaflow-transport", "grpc"));
        assertEquals("1", m.get(Metadatos.TIMESTAMP));
        assertEquals("Bearer t", m.get(Metadatos.AUTORIZACION));
        assertEquals("grpc-web", m.get(Metadatos.TRANSPORTE));
        assertFalse(m.containsKey(Metadata.Key.of("cookie", Metadata.ASCII_STRING_MARSHALLER)));
    }

    @Test
    void interpretaGrpcTimeoutConUnMaximo() {
        assertEquals(Duration.ofSeconds(30), pasarela.plazo("30S"));
        assertEquals(Duration.ofMillis(500), pasarela.plazo("500m"));
        assertEquals(Duration.ofSeconds(30), pasarela.plazo("2H"));
        assertEquals(Duration.ofSeconds(30), pasarela.plazo("abc"));
        assertEquals(Duration.ofSeconds(30), pasarela.plazo(null));
    }

    @Test
    void reconoceLasPeticionesGrpcWeb() {
        assertTrue(PasarelaGrpcWeb.esLlamadaGrpcWeb("POST", "application/grpc-web+proto"));
        assertTrue(PasarelaGrpcWeb.esLlamadaGrpcWeb("post", "application/grpc-web"));
        assertFalse(PasarelaGrpcWeb.esLlamadaGrpcWeb("GET", "application/grpc-web+proto"));
        assertFalse(PasarelaGrpcWeb.esLlamadaGrpcWeb("POST", "application/json"));
        assertFalse(PasarelaGrpcWeb.esLlamadaGrpcWeb("POST", null));
    }
}

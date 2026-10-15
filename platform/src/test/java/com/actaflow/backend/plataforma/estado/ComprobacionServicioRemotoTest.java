package com.actaflow.backend.plataforma.estado;

import static com.actaflow.backend.plataforma.soporte.ServiciosFalsos.SECRETO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.actaflow.backend.plataforma.grpc.ClienteGrpc;
import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.backend.plataforma.grpc.Transporte;
import com.actaflow.backend.plataforma.grpcweb.PasarelaGrpcWeb;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.backend.plataforma.soporte.ServidorHttpGrpcWeb;
import com.actaflow.contracts.system.v1.ComponentKind;
import com.actaflow.contracts.system.v1.ComponentStatus;
import com.actaflow.contracts.system.v1.HealthState;
import com.actaflow.contracts.system.v1.ServiceStatus;
import com.actaflow.contracts.system.v1.Transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Un servicio "legal-case" comprueba a otro servicio "motor" por los dos transportes reales:
 * gRPC nativo (HTTP/2) y gRPC-Web (HTTP/1.1 a traves de la pasarela).
 */
class ComprobacionServicioRemotoTest {

    private static final Duration LIMITE = Duration.ofSeconds(3);
    private final FirmaHmac firma = new FirmaHmac(SECRETO, 300, Clock.systemUTC());
    private ServidorGrpc motor;
    private ServidorHttpGrpcWeb web;

    @BeforeEach
    void iniciar() {
        VerificadorEstado verificador = new VerificadorEstado("ai-engine-falso", "9.9.9", List.of(), Clock.systemUTC());
        motor = new ServidorGrpc(List.of(new ServicioEstado(verificador)), firma, 0, 1 << 20);
        motor.iniciar();
        web = ServidorHttpGrpcWeb.iniciar(new PasarelaGrpcWeb(motor.canalEnMemoria(), motor.tiposDeMetodo(), 1 << 20,
                Duration.ofSeconds(30)));
    }

    @AfterEach
    void apagar() {
        web.close();
        motor.close();
    }

    private ClienteGrpc cliente(String transporte, FirmaHmac f) {
        return ClienteGrpc.crear(transporte, "127.0.0.1:" + motor.puertoNativo(), web.url(), f,
                Duration.ofSeconds(2), Duration.ofSeconds(10), 1 << 20);
    }

    @Test
    void porGrpcNativoAnidaElEstadoDelOtroServicio() {
        try (ClienteGrpc cliente = cliente("grpc", firma)) {
            assertEquals(Transporte.GRPC, cliente.transporte());
            ComponentStatus componente = new ComprobacionServicioRemoto("ai-engine", cliente.canal(), LIMITE)
                    .comprobar();
            assertEquals(HealthState.HEALTH_STATE_OK, componente.getState());
            assertEquals("9.9.9", componente.getVersion());
            assertEquals("ai-engine-falso responde", componente.getDetail());
            assertEquals(Transport.TRANSPORT_GRPC, componente.getNested().getServedVia());
            assertTrue(componente.getLatencyMs() >= 1);
        }
    }

    @Test
    void porGrpcWebTambienYElOtroServicioSabeComoLlegoLaLlamada() {
        try (ClienteGrpc cliente = cliente(" GRPC-WEB ", firma)) {
            assertEquals(Transporte.GRPC_WEB, cliente.transporte());
            ServiceStatus anidado = new ComprobacionServicioRemoto("ai-engine", cliente.canal(), LIMITE).comprobar()
                    .getNested();
            assertEquals(Transport.TRANSPORT_GRPC_WEB, anidado.getServedVia());
        }
    }

    @Test
    void conOtroSecretoQuedaDownConUnaExplicacionUtil() {
        FirmaHmac otra = new FirmaHmac("otro-secreto-distinto-de-al-menos-32-caracteres", 300, Clock.systemUTC());
        for (String transporte : List.of("grpc", "grpc-web")) {
            try (ClienteGrpc cliente = cliente(transporte, otra)) {
                ComponentStatus componente = new ComprobacionServicioRemoto("ai-engine", cliente.canal(), LIMITE)
                        .comprobar();
                assertEquals(HealthState.HEALTH_STATE_DOWN, componente.getState(), transporte);
                assertEquals("Firma rechazada: revisa el secreto compartido", componente.getDetail(), transporte);
            }
        }
    }

    @Test
    void sinNadieEscuchandoQuedaDownPorAmbosTransportes() throws Exception {
        int libre;
        try (ServerSocket s = new ServerSocket(0)) {
            libre = s.getLocalPort();
        }
        for (String transporte : List.of("grpc", "grpc-web")) {
            try (ClienteGrpc cliente = ClienteGrpc.crear(transporte, "127.0.0.1:" + libre,
                    URI.create("http://127.0.0.1:" + libre), firma, Duration.ofSeconds(1), Duration.ofSeconds(2),
                    1 << 20)) {
                ComponentStatus componente = new ComprobacionServicioRemoto("ai-engine", cliente.canal(), LIMITE)
                        .comprobar();
                assertEquals(HealthState.HEALTH_STATE_DOWN, componente.getState(), transporte);
                assertTrue(componente.getDetail().contains("despertando"), componente.getDetail());
            }
        }
    }

    @Test
    void elIdYElTipoSonLosDelComponente() {
        try (ClienteGrpc cliente = cliente("grpc", firma)) {
            ComprobacionServicioRemoto comprobacion = new ComprobacionServicioRemoto("ai-engine", cliente.canal(),
                    LIMITE);
            assertEquals("ai-engine", comprobacion.id());
            assertEquals(ComponentKind.COMPONENT_KIND_SERVICE, comprobacion.tipo());
            assertEquals(LIMITE, comprobacion.limite());
        }
    }

    @Test
    void unTransporteDesconocidoOIncompletoEsUnErrorDeConfiguracion() {
        assertThrows(IllegalArgumentException.class, () -> ClienteGrpc.interpretar("http"));
        assertThrows(IllegalArgumentException.class, () -> ClienteGrpc.interpretar(null));
        assertThrows(IllegalArgumentException.class, () -> ClienteGrpc.crear("grpc-web", null, null, firma,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1024));
        assertThrows(IllegalArgumentException.class, () -> ClienteGrpc.crear("grpc", " ", null, firma,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1024));
    }
}

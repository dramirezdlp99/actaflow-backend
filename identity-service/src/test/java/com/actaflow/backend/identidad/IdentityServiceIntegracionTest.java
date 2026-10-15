package com.actaflow.backend.identidad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.actaflow.backend.plataforma.grpc.ClienteGrpc;
import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.backend.plataforma.grpcweb.TramasGrpcWeb;
import com.actaflow.backend.plataforma.grpcweb.TramasGrpcWeb.Trama;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.contracts.system.v1.ComponentKind;
import com.actaflow.contracts.system.v1.ComponentStatus;
import com.actaflow.contracts.system.v1.GetServiceStatusRequest;
import com.actaflow.contracts.system.v1.HealthState;
import com.actaflow.contracts.system.v1.ServiceStatus;
import com.actaflow.contracts.system.v1.ServiceStatusServiceGrpc;
import com.actaflow.contracts.system.v1.Transport;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Arranque completo contra PostgreSQL real. El BFF se simula con los stubs generados desde el
 * contrato, por los dos transportes: gRPC-Web (puerto web) y gRPC nativo (HTTP/2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IdentityServiceIntegracionTest {

    /** El mismo secreto falso y publico de application-test.yaml. */
    private static final String SECRETO_BFF = "secreto-de-prueba-publico-solo-para-tests-0123456789";
    private static final FirmaHmac FIRMA_BFF = new FirmaHmac(SECRETO_BFF, 300, Clock.systemUTC());

    @Value("${local.server.port}")
    private int puerto;

    @Autowired
    private ServidorGrpc servidorGrpc;

    @Autowired
    private JdbcTemplate jdbc;

    private ClienteGrpc cliente(String transporte, FirmaHmac firma) {
        return ClienteGrpc.crear(transporte, "127.0.0.1:" + servidorGrpc.puertoNativo(),
                URI.create("http://127.0.0.1:" + puerto), firma, Duration.ofSeconds(2), Duration.ofSeconds(20),
                1 << 20);
    }

    private ServiceStatus estado(String transporte) {
        try (ClienteGrpc cliente = cliente(transporte, FIRMA_BFF)) {
            return ServiceStatusServiceGrpc.newBlockingStub(cliente.canal())
                    .getServiceStatus(GetServiceStatusRequest.getDefaultInstance()).getStatus();
        }
    }

    @Test
    void porGrpcWebInformaSuEstadoYElDeSuEsquema() {
        ServiceStatus estado = estado("grpc-web");
        assertEquals("identity-service", estado.getServiceName());
        assertFalse(estado.getVersion().isBlank());
        assertEquals(HealthState.HEALTH_STATE_OK, estado.getState());
        assertEquals(Transport.TRANSPORT_GRPC_WEB, estado.getServedVia());
        assertTrue(estado.getCheckedAt().getSeconds() > 0);
        ComponentStatus baseDatos = estado.getComponents(0);
        assertEquals("database", baseDatos.getId());
        assertEquals(ComponentKind.COMPONENT_KIND_DATABASE, baseDatos.getKind());
        assertEquals(HealthState.HEALTH_STATE_OK, baseDatos.getState());
        assertEquals("2", baseDatos.getVersion());
    }

    @Test
    void porGrpcNativoTambienYSabeQueLlegoPorHttp2() {
        assertTrue(servidorGrpc.puertoNativo() > 0);
        assertEquals(Transport.TRANSPORT_GRPC, estado("grpc").getServedVia());
    }

    @Test
    void sinLaFirmaDelBffSeRechazaPorAmbosTransportes() {
        FirmaHmac otra = new FirmaHmac("otro-secreto-distinto-de-al-menos-32-caracteres", 300, Clock.systemUTC());
        for (String transporte : List.of("grpc-web", "grpc")) {
            try (ClienteGrpc cliente = cliente(transporte, otra)) {
                StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                        () -> ServiceStatusServiceGrpc.newBlockingStub(cliente.canal())
                                .getServiceStatus(GetServiceStatusRequest.getDefaultInstance()));
                assertEquals(Status.Code.UNAUTHENTICATED, e.getStatus().getCode(), transporte);
            }
        }
    }

    @Test
    void flywayMigroSuPropioEsquema() {
        List<String> versiones = jdbc.queryForList(
                "select version from identity.flyway_schema_history where success and version is not null"
                        + " order by installed_rank",
                String.class);
        assertEquals(List.of("1", "2"), versiones);
    }

    @Test
    void elPrimerArranqueCreoLaOrganizacionYLaInvitacionDelAdministrador() {
        assertEquals(1, jdbc.queryForObject("select count(*) from identity.organization", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from identity.invitation where role = 'ADMIN'"
                + " and email = 'admin@prueba.test'", Integer.class));
    }

    @Test
    void elChequeoDeSaludEsPublicoNoRevelaDetallesYLlevaCabecerasDeSeguridad() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> respuesta = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + "/actuator/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, respuesta.statusCode());
            String cuerpo = respuesta.body().replace(" ", "");
            assertTrue(cuerpo.contains("\"status\":\"UP\""), cuerpo);
            assertFalse(cuerpo.contains("\"components\""), cuerpo);
            assertEquals("nosniff", respuesta.headers().firstValue("x-content-type-options").orElseThrow());
        }
    }

    @Test
    void lasRespuestasGrpcWebTambienLlevanCabecerasDeSeguridad() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> respuesta = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto
                            + "/actaflow.system.v1.ServiceStatusService/GetServiceStatus"))
                    .header("Content-Type", "application/grpc-web+proto")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[5])).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, respuesta.statusCode());
            assertEquals("DENY", respuesta.headers().firstValue("x-frame-options").orElseThrow());
            List<Trama> tramas = TramasGrpcWeb.decodificar(respuesta.body(), 1 << 20);
            // Una trama vacia sin firma: el error viaja en el bloque de trailers del cuerpo.
            assertEquals("16", TramasGrpcWeb.leerTrailers(tramas.getLast().contenido()).get("grpc-status"));
        }
    }
}

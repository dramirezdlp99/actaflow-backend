package com.actaflow.backend.expedientes.estado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.actaflow.backend.expedientes.soporte.ClienteBff;
import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.contracts.system.v1.ComponentStatus;
import com.actaflow.contracts.system.v1.HealthState;
import com.actaflow.contracts.system.v1.ServiceStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * El motor apunta a un puerto sin servicio (application-test.yaml): el servicio arranca igual,
 * aparece DEGRADED y explica que el motor puede estar despertando, sin filtrar errores internos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EstadoSinMotorIntegracionTest {

    @Value("${local.server.port}")
    private int puerto;

    @Autowired
    private ServidorGrpc servidorGrpc;

    @Test
    void sinMotorElServicioEstaDegradadoPeroResponde() {
        ServiceStatus estado = ClienteBff.estado("grpc-web", puerto, servidorGrpc.puertoNativo());
        assertEquals(HealthState.HEALTH_STATE_DEGRADED, estado.getState());
        assertEquals(HealthState.HEALTH_STATE_OK, estado.getComponents(0).getState());
        ComponentStatus motor = estado.getComponents(1);
        assertEquals(HealthState.HEALTH_STATE_DOWN, motor.getState());
        assertTrue(motor.getDetail().contains("despertando"), motor.getDetail());
        assertFalse(motor.hasNested());
    }

    @Test
    void elChequeoDeSaludNoDependeDelMotorNiDeLaBase() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> respuesta = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + "/actuator/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, respuesta.statusCode());
            assertTrue(respuesta.body().replace(" ", "").contains("\"status\":\"UP\""), respuesta.body());
            assertEquals("nosniff", respuesta.headers().firstValue("x-content-type-options").orElseThrow());
        }
    }
}

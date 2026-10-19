package com.actaflow.backend.expedientes.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/** La configuracion invalida detiene el arranque con un mensaje claro, nunca en la primera llamada. */
class ExpedientesPropertiesTest {

    private static final String BFF = "secreto-de-prueba-publico-solo-para-tests-0123456789";
    private static final String MOTOR = "secreto-motor-de-prueba-publico-solo-para-tests-01";

    private static ExpedientesProperties crear(String secretoBff, String secretoMotor, String transporte,
                                               String destino, URI url) {
        return new ExpedientesProperties("0.2.0", new ExpedientesProperties.Hmac(300),
                new ExpedientesProperties.Grpc(-1, 4096, Duration.ofSeconds(30)),
                new ExpedientesProperties.Bff(secretoBff),
                new ExpedientesProperties.Motor(transporte, destino, url, secretoMotor, Duration.ofSeconds(1),
                        Duration.ofSeconds(1)),
                new ExpedientesProperties.Estado(Duration.ofSeconds(1), Duration.ofSeconds(1)), IDENTIDAD);
    }

    private static final ExpedientesProperties.Identidad IDENTIDAD = new ExpedientesProperties.Identidad("grpc",
            "localhost:9198", null, Duration.ofSeconds(1), Duration.ofSeconds(1));

    private static void falla(String fragmento, Runnable accion) {
        IllegalStateException e = assertThrows(IllegalStateException.class, accion::run);
        assertTrue(e.getMessage().contains(fragmento), e.getMessage());
    }

    @Test
    void unaConfiguracionCompletaEsValidaConCualquierTransporte() {
        assertDoesNotThrow(() -> crear(BFF, MOTOR, "grpc", "localhost:8102", null));
        assertDoesNotThrow(() -> crear(BFF, MOTOR, "grpc-web", null, URI.create("https://motor.example")));
    }

    @Test
    void exigeSecretosLargosYDistintosPorPar() {
        falla("ACTAFLOW_LEGAL_CASE_HMAC_SECRET", () -> crear("", MOTOR, "grpc", "localhost:8102", null));
        falla("ACTAFLOW_ENGINE_HMAC_SECRET", () -> crear(BFF, "corto", "grpc", "localhost:8102", null));
        falla("deben ser distintos", () -> crear(BFF, BFF, "grpc", "localhost:8102", null));
    }

    @Test
    void exigeElDestinoDeIdentitySegunSuTransporte() {
        falla("ACTAFLOW_IDENTITY_URL", () -> new ExpedientesProperties("0.2.0", new ExpedientesProperties.Hmac(300),
                new ExpedientesProperties.Grpc(-1, 4096, Duration.ofSeconds(30)), new ExpedientesProperties.Bff(BFF),
                new ExpedientesProperties.Motor("grpc", "localhost:8102", null, MOTOR, Duration.ofSeconds(1),
                        Duration.ofSeconds(1)),
                new ExpedientesProperties.Estado(Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new ExpedientesProperties.Identidad("grpc-web", null, null, Duration.ofSeconds(1),
                        Duration.ofSeconds(1))));
    }

    @Test
    void exigeElDestinoQueNecesitaCadaTransporte() {
        falla("ACTAFLOW_ENGINE_URL", () -> crear(BFF, MOTOR, "grpc-web", "localhost:8102", null));
        falla("ACTAFLOW_ENGINE_GRPC_TARGET", () -> crear(BFF, MOTOR, "grpc", " ", null));
        assertThrows(IllegalArgumentException.class, () -> crear(BFF, MOTOR, "rest", "localhost:8102", null));
    }
}

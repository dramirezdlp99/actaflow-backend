package com.actaflow.backend.soporte;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.actaflow.backend.compartido.config.ActaFlowProperties;

/** Datos falsos y publicos compartidos por los tests. */
public final class DatosPrueba {

    /** Mismo secreto con el que se calcularon los vectores del BFF y del motor. */
    public static final String SECRETO_BFF = "secreto-de-prueba-publico-solo-para-tests-0123456789";
    public static final String SECRETO_MOTOR = "secreto-motor-de-prueba-publico-solo-para-tests-01";
    public static final long TS_VECTORES = 1_760_000_000L;

    private DatosPrueba() {
    }

    public static Clock relojFijo(long epochSegundos) {
        return Clock.fixed(Instant.ofEpochSecond(epochSegundos), ZoneOffset.UTC);
    }

    public static ActaFlowProperties propiedades(URI urlMotor, long maxBodyBytes) {
        return new ActaFlowProperties("0.1.0-test",
                new ActaFlowProperties.Hmac(300),
                new ActaFlowProperties.Bff(SECRETO_BFF, maxBodyBytes),
                new ActaFlowProperties.Motor(urlMotor, SECRETO_MOTOR, Duration.ofSeconds(1), Duration.ofSeconds(2)),
                new ActaFlowProperties.Estado(Duration.ofSeconds(2), Duration.ofSeconds(2)));
    }
}

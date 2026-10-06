package com.actaflow.backend.compartido.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion propia de ActaFlow. Todo llega por variables de entorno (ver application.yaml).
 */
@ConfigurationProperties(prefix = "actaflow")
public record ActaFlowProperties(String version, Hmac hmac, Bff bff, Motor motor, Estado estado) {

    private static final int LONGITUD_MINIMA_SECRETO = 32;

    public ActaFlowProperties {
        exigir(hmac != null && bff != null && motor != null && estado != null,
                "Falta configuracion de actaflow.*");
        exigirSecreto(bff.hmacSecret(), "ACTAFLOW_BFF_HMAC_SECRET");
        exigirSecreto(motor.hmacSecret(), "ACTAFLOW_ENGINE_HMAC_SECRET");
        exigir(motor.url() != null, "Falta ACTAFLOW_ENGINE_URL");
        exigir(hmac.toleranceSeconds() >= 30 && hmac.toleranceSeconds() <= 900,
                "ACTAFLOW_HMAC_TOLERANCE_SECONDS debe estar entre 30 y 900");
    }

    /** Parametros comunes de las firmas HMAC. */
    public record Hmac(long toleranceSeconds) {
    }

    /** Secreto con el que el BFF (Next.js) firma cada peticion al backend. */
    public record Bff(String hmacSecret, long maxBodyBytes) {
    }

    /** Motor de IA: URL base, secreto de firma y tiempos de espera. */
    public record Motor(URI url, String hmacSecret, Duration connectTimeout, Duration requestTimeout) {
    }

    /** Tiempos maximos de cada verificacion de GET /api/v1/status. */
    public record Estado(Duration timeoutBaseDatos, Duration timeoutMotor) {
    }

    private static void exigirSecreto(String secreto, String variable) {
        exigir(secreto != null && secreto.length() >= LONGITUD_MINIMA_SECRETO,
                variable + " debe tener al menos " + LONGITUD_MINIMA_SECRETO + " caracteres");
    }

    private static void exigir(boolean condicion, String mensaje) {
        if (!condicion) {
            throw new IllegalStateException(mensaje);
        }
    }
}

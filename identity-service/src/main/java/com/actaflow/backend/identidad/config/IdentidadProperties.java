package com.actaflow.backend.identidad.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion propia del servicio de identidad. Todo llega por variables de entorno
 * (ver application.yaml); los secretos nunca tienen valor por defecto.
 */
@ConfigurationProperties(prefix = "actaflow")
public record IdentidadProperties(String version, Hmac hmac, Grpc grpc, Bff bff, Estado estado) {

    private static final int LONGITUD_MINIMA_SECRETO = 32;

    public IdentidadProperties {
        exigir(hmac != null && grpc != null && bff != null && estado != null, "Falta configuracion de actaflow.*");
        exigir(bff.hmacSecret() != null && bff.hmacSecret().length() >= LONGITUD_MINIMA_SECRETO,
                "ACTAFLOW_IDENTITY_HMAC_SECRET debe tener al menos " + LONGITUD_MINIMA_SECRETO + " caracteres");
        exigir(hmac.toleranceSeconds() >= 30 && hmac.toleranceSeconds() <= 900,
                "ACTAFLOW_HMAC_TOLERANCE_SECONDS debe estar entre 30 y 900");
        exigir(grpc.nativePort() >= -1 && grpc.nativePort() <= 65_535,
                "ACTAFLOW_GRPC_NATIVE_PORT debe ser -1 (desactivado), 0 (libre) o un puerto");
        exigir(grpc.maxMessageBytes() >= 1024, "actaflow.grpc.max-message-bytes es demasiado pequeno");
    }

    /** Parametros comunes de las firmas HMAC. */
    public record Hmac(long toleranceSeconds) {
    }

    /**
     * Servidor gRPC.
     *
     * @param nativePort      puerto de gRPC nativo (HTTP/2); -1 lo desactiva (Render Free)
     * @param maxMessageBytes tamano maximo de un mensaje
     * @param webDeadline     plazo maximo de una llamada que llega por gRPC-Web
     */
    public record Grpc(int nativePort, int maxMessageBytes, Duration webDeadline) {
    }

    /** Secreto con el que el BFF (Next.js) firma cada llamada a este servicio. */
    public record Bff(String hmacSecret) {
    }

    /** Tiempo maximo de la comprobacion de la base de datos en GetServiceStatus. */
    public record Estado(Duration timeoutBaseDatos) {
    }

    private static void exigir(boolean condicion, String mensaje) {
        if (!condicion) {
            throw new IllegalStateException(mensaje);
        }
    }
}

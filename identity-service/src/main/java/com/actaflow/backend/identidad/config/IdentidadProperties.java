package com.actaflow.backend.identidad.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion propia del servicio de identidad. Todo llega por variables de entorno
 * (ver application.yaml); los secretos nunca tienen valor por defecto.
 */
@ConfigurationProperties(prefix = "actaflow")
public record IdentidadProperties(String version, Hmac hmac, Grpc grpc, Bff bff, Estado estado, Seguridad seguridad,
                                  Arranque arranque) {

    private static final int LONGITUD_MINIMA_SECRETO = 32;

    public IdentidadProperties {
        exigir(hmac != null && grpc != null && bff != null && estado != null && seguridad != null && arranque != null,
                "Falta configuracion de actaflow.*");
        exigirSecreto(seguridad.pepper(), "ACTAFLOW_IDENTITY_PEPPER");
        exigirSecreto(seguridad.tokenSecret(), "ACTAFLOW_IDENTITY_TOKEN_SECRET");
        exigir(!seguridad.pepper().equals(seguridad.tokenSecret()) && !seguridad.pepper().equals(bff.hmacSecret())
                && !seguridad.tokenSecret().equals(bff.hmacSecret()),
                "ACTAFLOW_IDENTITY_HMAC_SECRET, ACTAFLOW_IDENTITY_PEPPER y ACTAFLOW_IDENTITY_TOKEN_SECRET"
                        + " deben ser distintos");
        exigir(seguridad.accessTokenTtl() != null && !seguridad.accessTokenTtl().isNegative()
                && seguridad.accessTokenTtl().toMinutes() <= 60,
                "actaflow.seguridad.access-token-ttl debe ser de 1 a 60 min");
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

    /**
     * Secretos de la identidad. Nunca tienen valor por defecto y son distintos en local y produccion.
     *
     * @param pepper          ACTAFLOW_IDENTITY_PEPPER: entra en todos los hashes (verificadores, tokens)
     * @param tokenSecret     ACTAFLOW_IDENTITY_TOKEN_SECRET: semilla de la clave Ed25519 de los tokens
     * @param accessTokenTtl  vigencia del token de acceso
     */
    public record Seguridad(String pepper, String tokenSecret, Duration accessTokenTtl) {
    }

    /**
     * Primer arranque (opcional): organizacion e invitacion del primer administrador. Se usa una
     * sola vez; despues el administrador invita a los demas desde la aplicacion.
     */
    public record Arranque(String organizationName, String adminEmail, String invitationToken) {
    }

    /** Tiempo maximo de la comprobacion de la base de datos en GetServiceStatus. */
    public record Estado(Duration timeoutBaseDatos) {
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

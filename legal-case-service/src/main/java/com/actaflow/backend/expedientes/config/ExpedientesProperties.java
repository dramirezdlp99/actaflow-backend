package com.actaflow.backend.expedientes.config;

import java.net.URI;
import java.time.Duration;

import com.actaflow.backend.plataforma.grpc.ClienteGrpc;
import com.actaflow.backend.plataforma.grpc.Transporte;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion propia del servicio de expedientes. Todo llega por variables de entorno
 * (ver application.yaml); los secretos nunca tienen valor por defecto.
 */
@ConfigurationProperties(prefix = "actaflow")
public record ExpedientesProperties(String version, Hmac hmac, Grpc grpc, Bff bff, Motor motor, Estado estado,
                                    Identidad identidad, Sello sello) {

    private static final int LONGITUD_MINIMA_SECRETO = 32;

    public ExpedientesProperties {
        exigir(hmac != null && grpc != null && bff != null && motor != null && estado != null && identidad != null
                && sello != null, "Falta configuracion de actaflow.*");
        exigirSecreto(bff.hmacSecret(), "ACTAFLOW_LEGAL_CASE_HMAC_SECRET");
        exigirSecreto(motor.hmacSecret(), "ACTAFLOW_ENGINE_HMAC_SECRET");
        exigirSecreto(sello.secret(), "ACTAFLOW_SEAL_SECRET");
        exigir(!sello.secret().equals(bff.hmacSecret()) && !sello.secret().equals(motor.hmacSecret()),
                "ACTAFLOW_SEAL_SECRET debe ser distinto de los secretos HMAC");
        exigir(!bff.hmacSecret().equals(motor.hmacSecret()),
                "ACTAFLOW_LEGAL_CASE_HMAC_SECRET y ACTAFLOW_ENGINE_HMAC_SECRET deben ser distintos");
        exigir(hmac.toleranceSeconds() >= 30 && hmac.toleranceSeconds() <= 900,
                "ACTAFLOW_HMAC_TOLERANCE_SECONDS debe estar entre 30 y 900");
        exigir(grpc.nativePort() >= -1 && grpc.nativePort() <= 65_535,
                "ACTAFLOW_GRPC_NATIVE_PORT debe ser -1 (desactivado), 0 (libre) o un puerto");
        exigir(grpc.maxMessageBytes() >= 1024, "actaflow.grpc.max-message-bytes es demasiado pequeno");
        // Falla al arrancar, no en la primera llamada, si un transporte esta mal escrito.
        exigirDestino(motor.transport(), motor.grpcTarget(), motor.url(), "ENGINE");
        exigirDestino(identidad.transport(), identidad.grpcTarget(), identidad.url(), "IDENTITY");
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
     * Motor de IA (Python).
     *
     * @param transport      {@code grpc} (nativo, local y CI) o {@code grpc-web} (Render Free)
     * @param grpcTarget     {@code host:puerto} del gRPC nativo del motor
     * @param url            URL publica del motor, para gRPC-Web
     * @param hmacSecret     secreto del par expedientes-motor
     * @param connectTimeout tiempo maximo para conectar
     * @param requestTimeout plazo por defecto de una llamada
     */
    public record Motor(String transport, String grpcTarget, URI url, String hmacSecret, Duration connectTimeout,
                        Duration requestTimeout) {
    }

    /** Secreto del que se deriva la clave del sello de las actas (y su certificado). */
    public record Sello(String secret) {
    }

    /** Tiempos maximos de cada comprobacion de GetServiceStatus. */
    public record Estado(Duration timeoutBaseDatos, Duration timeoutMotor) {
    }

    /**
     * identity-service, solo para pedir la clave PUBLICA que verifica los tokens (sin secreto: ese
     * servicio de identity es publico, como un JWKS).
     *
     * @param transport      {@code grpc} (nativo, local y CI) o {@code grpc-web} (Render Free)
     * @param grpcTarget     {@code host:puerto} del gRPC nativo de identity
     * @param url            URL publica de identity, para gRPC-Web
     * @param connectTimeout tiempo maximo para conectar
     * @param requestTimeout plazo de la llamada (identity puede estar despertando en Render)
     */
    public record Identidad(String transport, String grpcTarget, URI url, Duration connectTimeout,
                            Duration requestTimeout) {
    }

    private static void exigirDestino(String transporte, String destino, URI url, String servicio) {
        if (ClienteGrpc.interpretar(transporte) == Transporte.GRPC_WEB) {
            exigir(url != null, "Con ACTAFLOW_" + servicio + "_TRANSPORT=grpc-web falta ACTAFLOW_" + servicio + "_URL");
        } else {
            exigir(destino != null && !destino.isBlank(),
                    "Con ACTAFLOW_" + servicio + "_TRANSPORT=grpc falta ACTAFLOW_" + servicio + "_GRPC_TARGET");
        }
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

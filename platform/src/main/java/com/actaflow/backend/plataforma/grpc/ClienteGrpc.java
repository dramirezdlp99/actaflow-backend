package com.actaflow.backend.plataforma.grpc;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.actaflow.backend.plataforma.grpcweb.CanalGrpcWeb;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;

import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;

/**
 * Canal firmado hacia otro microservicio, por el transporte que diga la configuracion:
 *
 * <ul>
 *   <li>{@code grpc}: gRPC nativo sobre HTTP/2 (local, CI);</li>
 *   <li>{@code grpc-web}: gRPC-Web sobre HTTP/1.1 (produccion en Render Free).</li>
 * </ul>
 *
 * <p>Quien lo usa solo ve un {@link Channel}: los stubs generados no cambian con el transporte.
 */
public final class ClienteGrpc implements AutoCloseable {

    private final Channel canal;
    private final AutoCloseable cierre;
    private final Transporte transporte;

    private ClienteGrpc(Channel base, AutoCloseable cierre, Transporte transporte, FirmaHmac firma) {
        this.canal = firma == null ? base : ClientInterceptors.intercept(base, new InterceptorClienteFirma(firma));
        this.cierre = cierre;
        this.transporte = transporte;
    }

    /**
     * @param transporte    {@code grpc} o {@code grpc-web}
     * @param destinoNativo {@code host:puerto} del servidor gRPC nativo (solo con {@code grpc})
     * @param urlWeb        URL del puerto web del servicio (solo con {@code grpc-web})
     * @param firma         secreto HMAC del par
     * @param conexion      tiempo maximo para conectar
     * @param plazo         plazo por defecto de las llamadas gRPC-Web
     * @param maxMensaje    tamano maximo de un mensaje de respuesta
     */
    public static ClienteGrpc crear(String transporte, String destinoNativo, URI urlWeb, FirmaHmac firma,
                                    Duration conexion, Duration plazo, int maxMensaje) {
        if (firma == null) {
            throw new IllegalArgumentException("Un cliente firmado necesita el secreto del par");
        }
        return crearCon(transporte, destinoNativo, urlWeb, firma, conexion, plazo, maxMensaje);
    }

    private static ClienteGrpc crearCon(String transporte, String destinoNativo, URI urlWeb, FirmaHmac firma,
                                        Duration conexion, Duration plazo, int maxMensaje) {
        Transporte elegido = interpretar(transporte);
        if (elegido == Transporte.GRPC_WEB) {
            if (urlWeb == null) {
                throw new IllegalArgumentException("El transporte grpc-web necesita la URL del servicio");
            }
            CanalGrpcWeb web = new CanalGrpcWeb(urlWeb, conexion, plazo, maxMensaje);
            return new ClienteGrpc(web, web, elegido, firma);
        }
        if (destinoNativo == null || destinoNativo.isBlank()) {
            throw new IllegalArgumentException("El transporte grpc necesita el destino host:puerto");
        }
        ManagedChannel nativo = Grpc.newChannelBuilder(destinoNativo, InsecureChannelCredentials.create())
                .maxInboundMessageSize(maxMensaje)
                .build();
        return new ClienteGrpc(nativo, () -> nativo.shutdownNow().awaitTermination(5, TimeUnit.SECONDS),
                elegido, firma);
    }

    /**
     * Cliente SIN firma HMAC, solo para servicios publicados con {@link ServicioPublico} (por
     * ejemplo, la clave publica que verifica los tokens). Mismos parametros que {@link #crear}.
     */
    public static ClienteGrpc crearPublico(String transporte, String destinoNativo, URI urlWeb, Duration conexion,
                                           Duration plazo, int maxMensaje) {
        return crearCon(transporte, destinoNativo, urlWeb, null, conexion, plazo, maxMensaje);
    }

    /** Lee {@code grpc} o {@code grpc-web}; cualquier otro valor es un error de configuracion. */
    public static Transporte interpretar(String valor) {
        String normalizado = valor == null ? "" : valor.strip().toLowerCase(Locale.ROOT);
        return switch (normalizado) {
            case "grpc" -> Transporte.GRPC;
            case "grpc-web" -> Transporte.GRPC_WEB;
            default -> throw new IllegalArgumentException(
                    "Transporte desconocido '" + valor + "': usa grpc o grpc-web");
        };
    }

    /** Canal firmado, listo para los stubs generados. */
    public Channel canal() {
        return canal;
    }

    public Transporte transporte() {
        return transporte;
    }

    @Override
    public void close() {
        try {
            cierre.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cerrar el canal gRPC", e);
        }
    }
}

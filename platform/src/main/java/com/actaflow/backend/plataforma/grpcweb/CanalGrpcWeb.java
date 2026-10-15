package com.actaflow.backend.plataforma.grpcweb;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.actaflow.backend.plataforma.grpcweb.TramasGrpcWeb.Trama;
import com.actaflow.backend.plataforma.grpcweb.TramasGrpcWeb.TramaInvalidaException;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Deadline;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

/**
 * Canal gRPC que habla gRPC-Web sobre HTTP/1.1.
 *
 * <p>Es un {@link Channel} normal: los stubs generados ({@code XxxServiceGrpc.newBlockingStub(canal)})
 * y los interceptores (como la firma HMAC) funcionan igual que sobre un canal nativo. Se usa en
 * produccion, donde Render Free solo deja pasar HTTP/1.1. Admite metodos unarios y con streaming del
 * servidor; el streaming del cliente no existe en gRPC-Web.
 */
public final class CanalGrpcWeb extends Channel implements AutoCloseable {

    private final URI base;
    private final HttpClient http;
    private final Duration plazoPorDefecto;
    private final int maxMensaje;
    private final ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param base            URL del servicio, por ejemplo {@code https://actaflow-ai-engine.onrender.com}
     * @param conexion        tiempo maximo para abrir la conexion
     * @param plazoPorDefecto plazo de una llamada que no fija el suyo
     * @param maxMensaje      tamano maximo de un mensaje de respuesta
     */
    public CanalGrpcWeb(URI base, Duration conexion, Duration plazoPorDefecto, int maxMensaje) {
        String esquema = base.getScheme();
        if (!"http".equals(esquema) && !"https".equals(esquema)) {
            throw new IllegalArgumentException("La URL gRPC-Web debe usar http o https: " + base);
        }
        this.base = base;
        this.plazoPorDefecto = plazoPorDefecto;
        this.maxMensaje = maxMensaje;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(conexion)
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(hilos)
                .build();
    }

    @Override
    public String authority() {
        return base.getAuthority();
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> metodo, CallOptions opciones) {
        return new Llamada<>(metodo, opciones);
    }

    @Override
    public void close() {
        hilos.shutdownNow();
        http.close();
    }

    /** Estado gRPC equivalente a una respuesta HTTP que no es 200 (especificacion gRPC-Web). */
    static Status estadoDesdeHttp(int estadoHttp) {
        Status estado = switch (estadoHttp) {
            case 400 -> Status.INTERNAL;
            case 401 -> Status.UNAUTHENTICATED;
            case 403 -> Status.PERMISSION_DENIED;
            case 404 -> Status.UNIMPLEMENTED;
            case 413 -> Status.RESOURCE_EXHAUSTED;
            case 429, 502, 503, 504 -> Status.UNAVAILABLE;
            default -> Status.UNKNOWN;
        };
        return estado.withDescription("HTTP " + estadoHttp);
    }

    /** Convierte los trailers de texto en metadatos gRPC (los -bin vienen en base64). */
    static Metadata metadatosDesde(Map<String, String> trailers) {
        Metadata metadatos = new Metadata();
        trailers.forEach((clave, valor) -> {
            if (clave.equals("grpc-status") || clave.equals("grpc-message")) {
                return;
            }
            try {
                if (clave.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                    metadatos.put(Metadata.Key.of(clave, Metadata.BINARY_BYTE_MARSHALLER),
                            Base64.getDecoder().decode(rellenar(valor)));
                } else {
                    metadatos.put(Metadata.Key.of(clave, Metadata.ASCII_STRING_MARSHALLER), valor);
                }
            } catch (IllegalArgumentException e) {
                // Un trailer malformado no debe tumbar la llamada: se ignora.
            }
        });
        return metadatos;
    }

    private static String rellenar(String base64) {
        return base64 + "=".repeat((4 - base64.length() % 4) % 4);
    }

    private final class Llamada<ReqT, RespT> extends ClientCall<ReqT, RespT> {

        private final MethodDescriptor<ReqT, RespT> metodo;
        private final CallOptions opciones;
        private final Queue<RespT> pendientes = new ConcurrentLinkedQueue<>();
        private final AtomicInteger demanda = new AtomicInteger();
        private final AtomicBoolean terminada = new AtomicBoolean();
        private Executor serial;
        private Listener<RespT> oyente;
        private Metadata cabeceras;
        private byte[] mensaje;
        private volatile InputStream respuesta;
        private volatile Status cierre;
        private volatile Metadata trailersCierre;
        private boolean cierreNotificado;

        Llamada(MethodDescriptor<ReqT, RespT> metodo, CallOptions opciones) {
            this.metodo = metodo;
            this.opciones = opciones;
        }

        @Override
        public void start(Listener<RespT> oyenteRespuestas, Metadata cabecerasIniciales) {
            this.oyente = oyenteRespuestas;
            this.cabeceras = cabecerasIniciales;
            Executor delOpciones = opciones.getExecutor();
            this.serial = new EjecutorSerial(delOpciones != null ? delOpciones : hilos);
            if (!metodo.getType().clientSendsOneMessage()) {
                terminar(Status.UNIMPLEMENTED.withDescription("gRPC-Web no admite streaming del cliente"),
                        new Metadata());
            }
        }

        @Override
        public void request(int cantidad) {
            demanda.addAndGet(cantidad);
            entregar();
        }

        @Override
        public void sendMessage(ReqT mensajeSaliente) {
            if (mensaje != null) {
                throw new IllegalStateException("Este metodo admite un solo mensaje del cliente");
            }
            try (InputStream flujo = metodo.streamRequest(mensajeSaliente)) {
                mensaje = flujo.readAllBytes();
            } catch (IOException e) {
                terminar(Status.INTERNAL.withDescription("No se pudo serializar el mensaje").withCause(e),
                        new Metadata());
            }
        }

        @Override
        public void halfClose() {
            if (terminada.get()) {
                return;
            }
            byte[] cuerpo = mensaje == null ? new byte[0] : mensaje;
            hilos.execute(() -> ejecutar(cuerpo));
        }

        @Override
        public void cancel(String motivo, Throwable causa) {
            InputStream abierta = respuesta;
            if (abierta != null) {
                try {
                    abierta.close();
                } catch (IOException e) {
                    // Se esta cancelando: no importa si el cierre falla.
                }
            }
            terminar(Status.CANCELLED.withDescription(motivo).withCause(causa), new Metadata());
        }

        private Duration plazo() {
            Deadline limite = opciones.getDeadline();
            if (limite == null) {
                return plazoPorDefecto;
            }
            long restante = limite.timeRemaining(TimeUnit.MILLISECONDS);
            return Duration.ofMillis(Math.max(1, restante));
        }

        private void ejecutar(byte[] cuerpo) {
            Duration plazo = plazo();
            HttpRequest.Builder peticion = HttpRequest.newBuilder(base.resolve("/" + metodo.getFullMethodName()))
                    .timeout(plazo)
                    .header("Content-Type", TramasGrpcWeb.TIPO)
                    .header("Accept", TramasGrpcWeb.TIPO)
                    .header("X-Grpc-Web", "1")
                    .header("grpc-timeout", Math.max(1, plazo.toMillis()) + "m")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(TramasGrpcWeb.datos(cuerpo)));
            for (String clave : cabeceras.keys()) {
                if (!clave.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                    for (String valor : cabeceras.getAll(Metadata.Key.of(clave, Metadata.ASCII_STRING_MARSHALLER))) {
                        peticion.header(clave, valor);
                    }
                }
            }
            try {
                HttpResponse<InputStream> http = CanalGrpcWeb.this.http.send(peticion.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                respuesta = http.body();
                try (InputStream flujo = http.body()) {
                    leerRespuesta(http, flujo);
                }
            } catch (HttpTimeoutException e) {
                terminar(Status.DEADLINE_EXCEEDED.withDescription("Sin respuesta dentro del plazo").withCause(e),
                        new Metadata());
            } catch (IOException e) {
                terminar(Status.UNAVAILABLE.withDescription("No se pudo conectar con " + authority()).withCause(e),
                        new Metadata());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                terminar(Status.CANCELLED.withDescription("Interrumpida"), new Metadata());
            }
        }

        private void leerRespuesta(HttpResponse<InputStream> http, InputStream flujo) throws IOException {
            if (http.statusCode() != 200) {
                terminar(estadoDesdeHttp(http.statusCode()), new Metadata());
                return;
            }
            String tipo = http.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            if (!tipo.startsWith("application/grpc-web")) {
                terminar(Status.INTERNAL.withDescription("Respuesta que no es gRPC-Web: " + tipo), new Metadata());
                return;
            }
            serial.execute(() -> oyente.onHeaders(new Metadata()));
            try {
                Trama trama;
                while ((trama = TramasGrpcWeb.leer(flujo, maxMensaje)) != null) {
                    if (trama.esTrailers()) {
                        Map<String, String> trailers = TramasGrpcWeb.leerTrailers(trama.contenido());
                        terminar(estadoDesde(trailers), metadatosDesde(trailers));
                        return;
                    }
                    pendientes.add(metodo.parseResponse(new ByteArrayInputStream(trama.contenido())));
                    entregar();
                }
            } catch (TramaInvalidaException e) {
                terminar(Status.INTERNAL.withDescription("Respuesta gRPC-Web malformada: " + e.getMessage()),
                        new Metadata());
                return;
            }
            // Sin trama de trailers: puede ser una respuesta "solo trailers" en las cabeceras HTTP.
            String codigo = http.headers().firstValue("grpc-status").orElse(null);
            if (codigo != null) {
                terminar(estadoDesde(Map.of("grpc-status", codigo,
                        "grpc-message", http.headers().firstValue("grpc-message").orElse(""))), new Metadata());
            } else {
                terminar(Status.INTERNAL.withDescription("La respuesta termino sin grpc-status"), new Metadata());
            }
        }

        private Status estadoDesde(Map<String, String> trailers) {
            Status estado;
            try {
                estado = Status.fromCodeValue(Integer.parseInt(trailers.getOrDefault("grpc-status", "2")));
            } catch (NumberFormatException e) {
                estado = Status.UNKNOWN;
            }
            String descripcion = trailers.get("grpc-message");
            return descripcion == null || descripcion.isEmpty() ? estado : estado.withDescription(descripcion);
        }

        private void terminar(Status estado, Metadata trailers) {
            if (terminada.compareAndSet(false, true)) {
                trailersCierre = trailers;
                cierre = estado;
                entregar();
            }
        }

        /** Entrega mensajes segun la demanda y, al final, el cierre; siempre en orden. */
        private void entregar() {
            if (serial == null) {
                return;
            }
            serial.execute(() -> {
                while (demanda.get() > 0 && !pendientes.isEmpty()) {
                    demanda.decrementAndGet();
                    oyente.onMessage(pendientes.poll());
                }
                if (cierre != null && !cierreNotificado
                        && (pendientes.isEmpty() || cierre.getCode() != Status.Code.OK)) {
                    cierreNotificado = true;
                    pendientes.clear();
                    oyente.onClose(cierre, trailersCierre);
                }
            });
        }
    }

    /** Ejecuta las tareas de una en una y en orden, sobre otro ejecutor. */
    static final class EjecutorSerial implements Executor {

        private final Executor base;
        private final Queue<Runnable> cola = new ArrayDeque<>();
        private boolean corriendo;

        EjecutorSerial(Executor base) {
            this.base = base;
        }

        @Override
        public void execute(Runnable tarea) {
            synchronized (cola) {
                cola.add(tarea);
                if (corriendo) {
                    return;
                }
                corriendo = true;
            }
            base.execute(this::vaciar);
        }

        private void vaciar() {
            while (true) {
                Runnable siguiente;
                synchronized (cola) {
                    siguiente = cola.poll();
                    if (siguiente == null) {
                        corriendo = false;
                        return;
                    }
                }
                siguiente.run();
            }
        }
    }
}

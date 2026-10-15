package com.actaflow.backend.plataforma.grpcweb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.actaflow.backend.plataforma.grpc.MarshallerBytes;
import com.actaflow.backend.plataforma.grpc.Metadatos;
import com.actaflow.backend.plataforma.grpcweb.TramasGrpcWeb.Trama;
import com.actaflow.backend.plataforma.grpcweb.TramasGrpcWeb.TramaInvalidaException;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

/**
 * Pasarela gRPC-Web hacia el servidor gRPC en memoria del mismo proceso.
 *
 * <p>Render Free solo expone HTTP/1.1 y gRPC nativo necesita HTTP/2. La pasarela recibe gRPC-Web por el
 * puerto web, desarma las tramas, reenvia la llamada al servidor en memoria (que tiene los mismos
 * servicios e interceptores que el nativo) y devuelve la respuesta como tramas gRPC-Web. Solo publica
 * servicios {@code actaflow.*}; el streaming del cliente no existe en gRPC-Web y se responde
 * UNIMPLEMENTED.
 */
public final class PasarelaGrpcWeb {

    /** Respuesta HTTP en construccion: el adaptador (Servlet, servidor del JDK...) la implementa. */
    public interface Salida {

        /** Fija el estado HTTP y el tipo de contenido. Se llama una sola vez, antes de escribir. */
        void comenzar(int estadoHttp, String tipoContenido) throws IOException;

        /** Flujo del cuerpo; cada trama se vacia apenas se escribe (streaming del servidor). */
        OutputStream cuerpo() throws IOException;
    }

    private static final System.Logger LOG = System.getLogger(PasarelaGrpcWeb.class.getName());
    private static final Pattern RUTA = Pattern.compile(
            "^/(actaflow\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)*\\.v[0-9]+\\.[A-Za-z0-9]+)/([A-Za-z0-9]+)$");
    private static final Pattern PLAZO = Pattern.compile("^([0-9]{1,8})([HMSmun])$");

    private final Channel enMemoria;
    private final Map<String, MethodDescriptor.MethodType> tipos;
    private final int maxMensaje;
    private final Duration plazoMaximo;

    public PasarelaGrpcWeb(Channel enMemoria, Map<String, MethodDescriptor.MethodType> tipos, int maxMensaje,
                           Duration plazoMaximo) {
        this.enMemoria = enMemoria;
        this.tipos = Map.copyOf(tipos);
        this.maxMensaje = maxMensaje;
        this.plazoMaximo = plazoMaximo;
    }

    /** Si la peticion HTTP es una llamada gRPC-Web que esta pasarela debe atender. */
    public static boolean esLlamadaGrpcWeb(String metodoHttp, String tipoContenido) {
        return "POST".equalsIgnoreCase(metodoHttp) && tipoContenido != null
                && tipoContenido.toLowerCase(Locale.ROOT).startsWith("application/grpc-web");
    }

    /**
     * Atiende una llamada.
     *
     * @param ruta          ruta HTTP, por ejemplo {@code /actaflow.system.v1.ServiceStatusService/GetServiceStatus}
     * @param tipoContenido Content-Type recibido
     * @param cabeceras     cabeceras HTTP con el nombre en minusculas
     * @param cuerpo        cuerpo HTTP
     * @param salida        respuesta
     */
    public void atender(String ruta, String tipoContenido, Map<String, String> cabeceras, InputStream cuerpo,
                        Salida salida) throws IOException {
        String tipo = tipoContenido == null ? "" : tipoContenido.split(";")[0].strip().toLowerCase(Locale.ROOT);
        if (!tipo.equals("application/grpc-web") && !tipo.equals(TramasGrpcWeb.TIPO)) {
            salida.comenzar(415, "text/plain");
            salida.cuerpo().write("Se esperaba application/grpc-web+proto".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Matcher coincidencia = RUTA.matcher(ruta);
        if (!coincidencia.matches()) {
            responderError(salida, Status.UNIMPLEMENTED.withDescription("Metodo desconocido"));
            return;
        }
        String nombreMetodo = coincidencia.group(1) + "/" + coincidencia.group(2);
        MethodDescriptor.MethodType tipoMetodo = tipos.get(nombreMetodo);
        if (tipoMetodo == null) {
            responderError(salida, Status.UNIMPLEMENTED.withDescription("Metodo desconocido"));
            return;
        }
        if (!tipoMetodo.clientSendsOneMessage()) {
            responderError(salida,
                    Status.UNIMPLEMENTED.withDescription("Este metodo requiere gRPC nativo sobre HTTP/2"));
            return;
        }

        byte[] mensaje;
        try {
            byte[] bytes = cuerpo.readNBytes(maxMensaje + 64);
            if (bytes.length > maxMensaje + 5) {
                salida.comenzar(413, "text/plain");
                return;
            }
            List<Trama> tramas = TramasGrpcWeb.decodificar(bytes, maxMensaje);
            List<Trama> datos = tramas.stream().filter(t -> !t.esTrailers()).toList();
            if (datos.size() > 1) {
                responderError(salida, Status.INVALID_ARGUMENT.withDescription("Se esperaba un solo mensaje"));
                return;
            }
            mensaje = datos.isEmpty() ? new byte[0] : datos.getFirst().contenido();
        } catch (TramaInvalidaException e) {
            responderError(salida, Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
            return;
        }

        reenviar(nombreMetodo, tipoMetodo, metadatos(cabeceras), plazo(cabeceras.get("grpc-timeout")), mensaje, salida);
    }

    private void reenviar(String nombreMetodo, MethodDescriptor.MethodType tipo, Metadata metadatos, Duration plazo,
                          byte[] mensaje, Salida salida) throws IOException {
        MethodDescriptor<byte[], byte[]> descriptor = MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(tipo)
                .setFullMethodName(nombreMetodo)
                .setRequestMarshaller(MarshallerBytes.INSTANCIA)
                .setResponseMarshaller(MarshallerBytes.INSTANCIA)
                .build();
        ClientCall<byte[], byte[]> llamada = enMemoria.newCall(descriptor,
                CallOptions.DEFAULT.withDeadlineAfter(plazo.toMillis(), TimeUnit.MILLISECONDS));
        BlockingQueue<Evento> eventos = new LinkedBlockingQueue<>();
        llamada.start(new ClientCall.Listener<>() {
            @Override
            public void onMessage(byte[] respuesta) {
                eventos.add(new Evento.Mensaje(respuesta));
                llamada.request(1);
            }

            @Override
            public void onClose(Status estado, Metadata trailers) {
                eventos.add(new Evento.Cierre(estado, trailers));
            }
        }, metadatos);
        llamada.request(1);
        llamada.sendMessage(mensaje);
        llamada.halfClose();

        salida.comenzar(200, TramasGrpcWeb.TIPO);
        OutputStream flujo = salida.cuerpo();
        try {
            while (true) {
                Evento evento = eventos.take();
                if (evento instanceof Evento.Mensaje m) {
                    flujo.write(TramasGrpcWeb.datos(m.bytes()));
                    flujo.flush();
                } else if (evento instanceof Evento.Cierre c) {
                    if (c.estado().getCode() == Status.Code.INTERNAL || c.estado().getCode() == Status.Code.UNKNOWN) {
                        LOG.log(Level.WARNING, "Error interno en {0}: {1}", nombreMetodo, c.estado(),
                                c.estado().getCause());
                    }
                    flujo.write(TramasGrpcWeb.trailers(c.estado().getCode().value(), c.estado().getDescription(),
                            c.trailers()));
                    flujo.flush();
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            llamada.cancel("Peticion interrumpida", e);
        } catch (IOException e) {
            // El cliente cerro la conexion: se cancela la llamada para no dejar trabajo colgado.
            llamada.cancel("El cliente cerro la conexion", e);
            throw e;
        }
    }

    private void responderError(Salida salida, Status estado) throws IOException {
        salida.comenzar(200, TramasGrpcWeb.TIPO);
        salida.cuerpo().write(TramasGrpcWeb.trailers(estado.getCode().value(), estado.getDescription(), null));
    }

    /** Solo se reenvian la firma, la sesion y el marcador de transporte, que pone la propia pasarela. */
    static Metadata metadatos(Map<String, String> cabeceras) {
        Metadata metadatos = new Metadata();
        cabeceras.forEach((nombre, valor) -> {
            String clave = nombre.toLowerCase(Locale.ROOT);
            boolean propia = clave.startsWith("x-actaflow-") && !clave.equals(Metadatos.TRANSPORTE.name());
            if ((propia || clave.equals("authorization")) && !clave.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                metadatos.put(Metadata.Key.of(clave, Metadata.ASCII_STRING_MARSHALLER), valor);
            }
        });
        metadatos.put(Metadatos.TRANSPORTE, Metadatos.VALOR_GRPC_WEB);
        return metadatos;
    }

    /** Convierte la cabecera grpc-timeout ("30S", "500m") en un plazo, con un maximo. */
    Duration plazo(String cabecera) {
        if (cabecera == null) {
            return plazoMaximo;
        }
        Matcher m = PLAZO.matcher(cabecera.strip());
        if (!m.matches()) {
            return plazoMaximo;
        }
        long cantidad = Long.parseLong(m.group(1));
        Duration pedido = switch (m.group(2)) {
            case "H" -> Duration.ofHours(cantidad);
            case "M" -> Duration.ofMinutes(cantidad);
            case "S" -> Duration.ofSeconds(cantidad);
            case "m" -> Duration.ofMillis(cantidad);
            case "u" -> Duration.ofNanos(cantidad * 1000);
            default -> Duration.ofNanos(cantidad);
        };
        return pedido.compareTo(plazoMaximo) > 0 ? plazoMaximo : pedido;
    }

    /** Lo que el servidor en memoria va devolviendo. */
    private sealed interface Evento {

        /** Un mensaje de respuesta. */
        record Mensaje(byte[] bytes) implements Evento {
        }

        /** El final de la llamada. */
        record Cierre(Status estado, Metadata trailers) implements Evento {
        }
    }
}

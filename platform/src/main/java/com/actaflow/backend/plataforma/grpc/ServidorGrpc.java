package com.actaflow.backend.plataforma.grpc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.actaflow.backend.plataforma.seguridad.FirmaHmac;

import io.grpc.BindableService;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;

/**
 * Servidor gRPC de un microservicio, con los mismos servicios e interceptores en dos transportes:
 *
 * <ul>
 *   <li>en memoria: lo usa la pasarela gRPC-Web del puerto web (produccion en Render Free);</li>
 *   <li>nativo sobre HTTP/2, opcional: local, CI y redes con HTTP/2.</li>
 * </ul>
 *
 * <p>Los servicios corren en hilos virtuales, asi pueden bloquear (JDBC, llamadas al motor) sin
 * agotar un pool de hilos.
 */
public final class ServidorGrpc implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ServidorGrpc.class.getName());

    private final List<ServerServiceDefinition> definiciones;
    private final Map<String, MethodDescriptor.MethodType> tipos = new HashMap<>();
    private final String nombreEnMemoria = "actaflow-" + UUID.randomUUID();
    private final int puertoNativoSolicitado;
    private final int maxMensajeBytes;
    private final ExecutorService ejecutor = Executors.newVirtualThreadPerTaskExecutor();
    private Server enMemoria;
    private Server nativo;
    private ManagedChannel canalEnMemoria;

    /**
     * @param servicios        implementaciones generadas desde el contrato; los envueltos en
     *                         {@link ServicioPublico} no exigen la firma HMAC
     * @param firma            verificador de la firma HMAC de las llamadas entrantes
     * @param puertoNativo     puerto del servidor nativo; negativo lo desactiva, 0 elige uno libre
     * @param maxMensajeBytes  tamano maximo de un mensaje
     */
    public ServidorGrpc(List<BindableService> servicios, FirmaHmac firma, int puertoNativo, int maxMensajeBytes) {
        InterceptorServidorFirma interceptor = new InterceptorServidorFirma(firma);
        List<ServerServiceDefinition> lista = new ArrayList<>();
        for (BindableService servicio : servicios) {
            ServerServiceDefinition bytes =
                    ServerInterceptors.useMarshalledMessages(servicio.bindService(), MarshallerBytes.INSTANCIA);
            lista.add(servicio instanceof ServicioPublico ? bytes : ServerInterceptors.intercept(bytes, interceptor));
            for (ServerMethodDefinition<?, ?> metodo : bytes.getMethods()) {
                MethodDescriptor<?, ?> descriptor = metodo.getMethodDescriptor();
                tipos.put(descriptor.getFullMethodName(), descriptor.getType());
            }
        }
        this.definiciones = List.copyOf(lista);
        this.puertoNativoSolicitado = puertoNativo;
        this.maxMensajeBytes = maxMensajeBytes;
    }

    public void iniciar() {
        try {
            InProcessServerBuilder memoria = InProcessServerBuilder.forName(nombreEnMemoria).executor(ejecutor)
                    .maxInboundMessageSize(maxMensajeBytes);
            definiciones.forEach(memoria::addService);
            enMemoria = memoria.build().start();
            canalEnMemoria = InProcessChannelBuilder.forName(nombreEnMemoria).directExecutor()
                    .maxInboundMessageSize(maxMensajeBytes).build();
            if (puertoNativoSolicitado >= 0) {
                var builder = Grpc.newServerBuilderForPort(puertoNativoSolicitado, InsecureServerCredentials.create())
                        .executor(ejecutor).maxInboundMessageSize(maxMensajeBytes);
                definiciones.forEach(builder::addService);
                nativo = builder.build().start();
                LOG.log(Level.INFO, "gRPC nativo (HTTP/2) escuchando en el puerto {0}", nativo.getPort());
            }
        } catch (IOException e) {
            close();
            throw new UncheckedIOException("No se pudo iniciar el servidor gRPC", e);
        }
    }

    /** Canal hacia el servidor en memoria; lo usa la pasarela gRPC-Web. */
    public ManagedChannel canalEnMemoria() {
        if (canalEnMemoria == null) {
            throw new IllegalStateException("El servidor gRPC no se ha iniciado");
        }
        return canalEnMemoria;
    }

    /** Tipo (unario, streaming...) de cada metodo publicado, por nombre completo. */
    public Map<String, MethodDescriptor.MethodType> tiposDeMetodo() {
        return Map.copyOf(tipos);
    }

    /** Puerto real del servidor nativo, o -1 si esta desactivado. */
    public int puertoNativo() {
        return nativo == null ? -1 : nativo.getPort();
    }

    @Override
    public void close() {
        if (canalEnMemoria != null) {
            canalEnMemoria.shutdownNow();
        }
        for (Server servidor : new Server[] {nativo, enMemoria}) {
            if (servidor != null) {
                servidor.shutdown();
                try {
                    if (!servidor.awaitTermination(5, TimeUnit.SECONDS)) {
                        servidor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    servidor.shutdownNow();
                }
            }
        }
        ejecutor.shutdownNow();
    }
}

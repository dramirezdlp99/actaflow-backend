package com.actaflow.backend.expedientes.soporte;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.actaflow.backend.plataforma.estado.Comprobacion;
import com.actaflow.backend.plataforma.estado.ServicioEstado;
import com.actaflow.backend.plataforma.estado.VerificadorEstado;
import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.backend.plataforma.grpcweb.PasarelaGrpcWeb;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.contracts.system.v1.ComponentKind;
import com.actaflow.contracts.system.v1.ComponentStatus;
import com.actaflow.contracts.system.v1.HealthState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Motor de IA falso que cumple el contrato real ({@code ServiceStatusService}) por los dos
 * transportes: gRPC nativo (HTTP/2) y gRPC-Web (HTTP/1.1). Exige la firma del par expedientes-motor.
 */
public final class MotorFalso implements AutoCloseable {

    /** El mismo secreto falso y publico de application-test.yaml. */
    public static final String SECRETO = "secreto-motor-de-prueba-publico-solo-para-tests-01";

    private final ServidorGrpc grpc;
    private final HttpServer web;
    private final AtomicInteger comprobaciones = new AtomicInteger();

    private MotorFalso() {
        Comprobacion modeloVoz = new Comprobacion() {
            @Override
            public String id() {
                return "speech-model";
            }

            @Override
            public ComponentKind tipo() {
                return ComponentKind.COMPONENT_KIND_MODEL;
            }

            @Override
            public Duration limite() {
                return Duration.ofSeconds(1);
            }

            @Override
            public ComponentStatus comprobar() {
                comprobaciones.incrementAndGet();
                return ComponentStatus.newBuilder().setState(HealthState.HEALTH_STATE_OK)
                        .setDetail("Modelo de voz tiny listo").setVersion("tiny").build();
            }
        };
        VerificadorEstado verificador = new VerificadorEstado("ai-engine", "0.2.0", List.of(modeloVoz),
                Clock.systemUTC());
        grpc = new ServidorGrpc(List.of(new ServicioEstado(verificador)),
                new FirmaHmac(SECRETO, 300, Clock.systemUTC()), 0, 1 << 20);
        grpc.iniciar();
        PasarelaGrpcWeb pasarela = new PasarelaGrpcWeb(grpc.canalEnMemoria(), grpc.tiposDeMetodo(), 1 << 20,
                Duration.ofSeconds(30));
        try {
            web = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            grpc.close();
            throw new IllegalStateException("No se pudo iniciar el motor falso", e);
        }
        web.createContext("/", intercambio -> atender(pasarela, intercambio));
        web.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        web.start();
    }

    public static MotorFalso iniciar() {
        return new MotorFalso();
    }

    /** Destino {@code host:puerto} del gRPC nativo. */
    public String destinoNativo() {
        return "127.0.0.1:" + grpc.puertoNativo();
    }

    /** URL del puerto web (gRPC-Web). */
    public URI url() {
        return URI.create("http://127.0.0.1:" + web.getAddress().getPort());
    }

    /** Cuantas veces llego una llamada firmada hasta el servicio de estado. */
    public int comprobaciones() {
        return comprobaciones.get();
    }

    private static void atender(PasarelaGrpcWeb pasarela, HttpExchange intercambio) throws IOException {
        Map<String, String> cabeceras = new HashMap<>();
        intercambio.getRequestHeaders().forEach((nombre, valores) ->
                cabeceras.put(nombre.toLowerCase(Locale.ROOT), String.join(",", valores)));
        pasarela.atender(intercambio.getRequestURI().getPath(), cabeceras.get("content-type"), cabeceras,
                intercambio.getRequestBody(), new PasarelaGrpcWeb.Salida() {
                    @Override
                    public void comenzar(int estadoHttp, String tipoContenido) throws IOException {
                        intercambio.getResponseHeaders().set("Content-Type", tipoContenido);
                        intercambio.sendResponseHeaders(estadoHttp, 0);
                    }

                    @Override
                    public OutputStream cuerpo() {
                        return intercambio.getResponseBody();
                    }
                });
        intercambio.close();
    }

    @Override
    public void close() {
        web.stop(0);
        grpc.close();
    }
}

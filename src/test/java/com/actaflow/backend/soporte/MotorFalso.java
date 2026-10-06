package com.actaflow.backend.soporte;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Motor de IA falso sobre el servidor HTTP del JDK. Respeta el contrato real de
 * {@code GET /health} y guarda la ultima peticion para inspeccionar firma y protocolo.
 */
public final class MotorFalso implements AutoCloseable {

    public static final String HEALTH_LISTO = """
            {"servicio":"actaflow-ai-engine","version":"0.1.0","estado":"ok","entorno":"local",
             "modelos":{"asr":{"configurado":"tiny","disponibles":["tiny"],"listo":true},
                        "nlp":{"disponibles":["es_core_news_sm"]}},"extra":"ignorado"}
            """;
    public static final String HEALTH_SIN_MODELO = """
            {"servicio":"actaflow-ai-engine","version":"0.1.0","estado":"degradado","entorno":"local",
             "modelos":{"asr":{"configurado":"base","disponibles":[],"listo":false},"nlp":{"disponibles":[]}}}
            """;

    private final HttpServer servidor;
    private final AtomicReference<Integer> estado = new AtomicReference<>(200);
    private final AtomicReference<String> cuerpo = new AtomicReference<>(HEALTH_LISTO);
    private final AtomicReference<Duration> demora = new AtomicReference<>(Duration.ZERO);
    private final Map<String, String> ultimasCabeceras = new ConcurrentHashMap<>();
    private final AtomicReference<String> ultimoProtocolo = new AtomicReference<>();
    private final AtomicReference<String> ultimaRuta = new AtomicReference<>();

    private MotorFalso(HttpServer servidor) {
        this.servidor = servidor;
    }

    public static MotorFalso iniciar() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            MotorFalso motor = new MotorFalso(servidor);
            servidor.createContext("/", motor::atender);
            servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            servidor.start();
            return motor;
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo iniciar el motor falso", e);
        }
    }

    private void atender(HttpExchange intercambio) throws IOException {
        ultimasCabeceras.clear();
        intercambio.getRequestHeaders().forEach((nombre, valores) ->
                ultimasCabeceras.put(nombre.toLowerCase(), String.join(",", valores)));
        ultimoProtocolo.set(intercambio.getProtocol());
        ultimaRuta.set(intercambio.getRequestURI().toString());
        Duration espera = demora.get();
        if (!espera.isZero()) {
            try {
                Thread.sleep(espera);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] datos = cuerpo.get().getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().add("Content-Type", "application/json");
        intercambio.sendResponseHeaders(estado.get(), datos.length);
        try (OutputStream salida = intercambio.getResponseBody()) {
            salida.write(datos);
        }
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort());
    }

    public MotorFalso responder(int codigo, String json) {
        estado.set(codigo);
        cuerpo.set(json);
        return this;
    }

    public MotorFalso tardar(Duration duracion) {
        demora.set(duracion);
        return this;
    }

    public String cabecera(String nombre) {
        return ultimasCabeceras.get(nombre.toLowerCase());
    }

    public String ultimoProtocolo() {
        return ultimoProtocolo.get();
    }

    public String ultimaRuta() {
        return ultimaRuta.get();
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}

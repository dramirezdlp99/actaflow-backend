package com.actaflow.backend.plataforma.soporte;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

import com.actaflow.backend.plataforma.grpcweb.PasarelaGrpcWeb;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Expone una {@link PasarelaGrpcWeb} con el servidor HTTP del JDK (HTTP/1.1), para los tests. */
public final class ServidorHttpGrpcWeb implements AutoCloseable {

    private final HttpServer servidor;

    private ServidorHttpGrpcWeb(HttpServer servidor) {
        this.servidor = servidor;
    }

    /** Sirve la pasarela en un puerto libre. */
    public static ServidorHttpGrpcWeb iniciar(PasarelaGrpcWeb pasarela) {
        return iniciar(intercambio -> atender(pasarela, intercambio));
    }

    /** Responde siempre con el estado HTTP indicado (para probar errores de red o de proxy). */
    public static ServidorHttpGrpcWeb queResponde(int estadoHttp) {
        return iniciar(intercambio -> {
            intercambio.getRequestBody().readAllBytes();
            intercambio.sendResponseHeaders(estadoHttp, -1);
            intercambio.close();
        });
    }

    private static ServidorHttpGrpcWeb iniciar(com.sun.net.httpserver.HttpHandler manejador) {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/", manejador);
            servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            servidor.start();
            return new ServidorHttpGrpcWeb(servidor);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo iniciar el servidor HTTP de pruebas", e);
        }
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

    public URI url() {
        return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort());
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}

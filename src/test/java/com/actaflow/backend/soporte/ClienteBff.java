package com.actaflow.backend.soporte;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;

import com.actaflow.backend.compartido.seguridad.FirmaHmac;

/** Cliente HTTP de pruebas que se comporta como el BFF: firma cada peticion. */
public final class ClienteBff {

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final FirmaHmac firma = new FirmaHmac(DatosPrueba.SECRETO_BFF, 300, Clock.systemUTC());
    private final String base;

    public ClienteBff(int puerto) {
        this.base = "http://127.0.0.1:" + puerto;
    }

    public HttpResponse<String> getFirmado(String ruta) {
        HttpRequest.Builder peticion = HttpRequest.newBuilder(URI.create(base + ruta))
                .timeout(Duration.ofSeconds(30)).GET();
        firma.cabeceras("GET", ruta, new byte[0]).forEach(peticion::header);
        return enviar(peticion.build());
    }

    public HttpResponse<String> getSinFirma(String ruta) {
        return enviar(HttpRequest.newBuilder(URI.create(base + ruta)).timeout(Duration.ofSeconds(30)).GET().build());
    }

    private HttpResponse<String> enviar(HttpRequest peticion) {
        try {
            return http.send(peticion, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

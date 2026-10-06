package com.actaflow.backend.estado.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.actaflow.backend.compartido.seguridad.FirmaHmac;
import com.actaflow.backend.estado.application.VerificadorMotorIa;
import com.actaflow.backend.estado.domain.VerificacionComponente;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente HTTP del motor de IA. Cada peticion va firmada con HMAC.
 *
 * <p>Se fuerza HTTP/1.1: por defecto el cliente de Java intenta subir a HTTP/2 con
 * {@code Upgrade: h2c} y uvicorn llena su consola de advertencias.
 */
public class MotorIaHttpCliente implements VerificadorMotorIa {

    static final String RUTA_HEALTH = "/health";
    private static final Logger LOG = LoggerFactory.getLogger(MotorIaHttpCliente.class);
    private static final byte[] SIN_CUERPO = new byte[0];

    private final HttpClient http;
    private final URI base;
    private final FirmaHmac firma;
    private final Duration timeoutPeticion;
    private final JsonMapper json = JsonMapper.builder().build();

    public MotorIaHttpCliente(URI base, FirmaHmac firma, Duration timeoutConexion, Duration timeoutPeticion) {
        this.base = base;
        this.firma = firma;
        this.timeoutPeticion = timeoutPeticion;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeoutConexion)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public VerificacionComponente verificar() {
        long inicio = System.nanoTime();
        try {
            HttpResponse<String> respuesta = http.send(peticionFirmada("GET", RUTA_HEALTH),
                    HttpResponse.BodyHandlers.ofString());
            long ms = transcurridoMs(inicio);
            if (respuesta.statusCode() != 200) {
                return VerificacionComponente.caido(ms, "El motor respondio HTTP " + respuesta.statusCode());
            }
            return interpretar(json.readValue(respuesta.body(), SaludMotor.class), ms);
        } catch (HttpTimeoutException e) {
            return VerificacionComponente.caido(transcurridoMs(inicio), "Sin respuesta (puede estar despertando)");
        } catch (IOException e) {
            LOG.warn("No se pudo conectar con el motor de IA: {}", e.getMessage());
            return VerificacionComponente.caido(transcurridoMs(inicio), "No se pudo conectar con el motor");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return VerificacionComponente.caido(transcurridoMs(inicio), "Verificacion interrumpida");
        } catch (RuntimeException e) {
            // Jackson 3 lanza excepciones no comprobadas ante un JSON ilegible.
            LOG.warn("Respuesta ilegible del motor de IA: {}", e.getMessage());
            return VerificacionComponente.caido(transcurridoMs(inicio), "Respuesta ilegible del motor");
        }
    }

    static VerificacionComponente interpretar(SaludMotor salud, long ms) {
        SaludMotor.Asr asr = salud.modelos() == null ? null : salud.modelos().asr();
        if (asr == null) {
            return VerificacionComponente.degradado(ms, "El motor no informa sus modelos", salud.version());
        }
        if (!asr.listo()) {
            return VerificacionComponente.degradado(ms,
                    "Modelo de voz " + asr.configurado() + " no instalado", salud.version());
        }
        return VerificacionComponente.ok(ms, "Modelo de voz " + asr.configurado() + " listo", salud.version());
    }

    HttpRequest peticionFirmada(String metodo, String ruta) {
        HttpRequest.Builder constructor = HttpRequest.newBuilder(base.resolve(ruta))
                .timeout(timeoutPeticion)
                .method(metodo, HttpRequest.BodyPublishers.noBody())
                .header("Accept", "application/json");
        for (Map.Entry<String, String> cabecera : firma.cabeceras(metodo, ruta, SIN_CUERPO).entrySet()) {
            constructor.header(cabecera.getKey(), cabecera.getValue());
        }
        return constructor.build();
    }

    private static long transcurridoMs(long inicioNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicioNanos);
    }
}

package com.actaflow.backend.estado.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.List;

import com.actaflow.backend.compartido.web.ApiError;
import com.actaflow.backend.soporte.ClienteBff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.json.JsonMapper;

/**
 * Arranque completo contra PostgreSQL real. El motor apunta a un puerto sin servicio,
 * asi que debe aparecer CAIDO y el sistema DEGRADADO.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EstadoIntegracionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int puerto;

    @Autowired
    private JdbcTemplate jdbc;

    private ClienteBff cliente;

    @BeforeEach
    void preparar() {
        cliente = new ClienteBff(puerto);
    }

    @Test
    void flywayAplicoLaMigracionV1() {
        List<String> versiones = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);
        assertTrue(versiones.contains("1"), versiones.toString());
        Integer tablas = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'trabajo'", Integer.class);
        assertEquals(1, tablas.intValue());
    }

    @Test
    void actuatorHealthEsPublicoYNoRevelaDetalles() {
        HttpResponse<String> respuesta = cliente.getSinFirma("/actuator/health");

        assertEquals(200, respuesta.statusCode());
        String cuerpo = respuesta.body().replace(" ", "");
        assertTrue(cuerpo.contains("\"status\":\"UP\""), cuerpo);
        // Spring Boot 4 agrega los nombres de los grupos de sondas (liveness, readiness), que no
        // revelan nada. Lo que no debe aparecer son los componentes ni sus detalles (BD, disco).
        assertFalse(cuerpo.contains("\"components\""), cuerpo);
        assertFalse(cuerpo.contains("\"details\""), cuerpo);
        assertFalse(cuerpo.contains("\"db\""), cuerpo);
    }

    @Test
    void statusSinFirmaResponde401ConApiError() {
        HttpResponse<String> respuesta = cliente.getSinFirma("/api/v1/status");

        assertEquals(401, respuesta.statusCode());
        ApiError error = JSON.readValue(respuesta.body(), ApiError.class);
        assertEquals(401, error.estado());
        assertEquals("firma_invalida", error.codigo());
        assertEquals("/api/v1/status", error.ruta());
        assertFalse(respuesta.body().contains("FALTA_CABECERA"), "el motivo no se revela al cliente");
    }

    @Test
    void statusFirmadoReportaLaBaseOkYElMotorCaido() {
        HttpResponse<String> respuesta = cliente.getFirmado("/api/v1/status");

        assertEquals(200, respuesta.statusCode());
        RespuestaEstado estado = JSON.readValue(respuesta.body(), RespuestaEstado.class);
        assertEquals("actaflow-backend", estado.servicio());
        assertEquals("DEGRADADO", estado.estado());
        assertEquals("OK", estado.componentes().baseDatos().estado());
        assertEquals("1", estado.componentes().baseDatos().version());
        assertTrue(estado.componentes().baseDatos().detalle().contains("esquema v1"));
        assertEquals("CAIDO", estado.componentes().motorIa().estado());
    }

    @Test
    void rutaInexistenteFirmadaResponde404Uniforme() {
        HttpResponse<String> respuesta = cliente.getFirmado("/api/v1/no-existe");

        assertEquals(404, respuesta.statusCode());
        ApiError error = JSON.readValue(respuesta.body(), ApiError.class);
        assertEquals("no_encontrado", error.codigo());
        assertEquals("/api/v1/no-existe", error.ruta());
    }

    @Test
    void todasLasRespuestasLlevanCabecerasDeSeguridad() {
        for (HttpResponse<String> respuesta : List.of(cliente.getSinFirma("/actuator/health"),
                cliente.getSinFirma("/api/v1/status"), cliente.getFirmado("/api/v1/status"))) {
            assertEquals("nosniff", respuesta.headers().firstValue("X-Content-Type-Options").orElse(null));
            assertEquals("DENY", respuesta.headers().firstValue("X-Frame-Options").orElse(null));
            assertEquals("no-store", respuesta.headers().firstValue("Cache-Control").orElse(null));
            assertNull(respuesta.headers().firstValue("X-Powered-By").orElse(null));
        }
    }
}

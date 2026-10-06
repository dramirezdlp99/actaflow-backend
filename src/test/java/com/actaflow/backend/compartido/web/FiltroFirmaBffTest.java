package com.actaflow.backend.compartido.web;

import static com.actaflow.backend.soporte.DatosPrueba.SECRETO_BFF;
import static com.actaflow.backend.soporte.DatosPrueba.relojFijo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

import com.actaflow.backend.compartido.seguridad.FirmaHmac;
import com.actaflow.backend.compartido.seguridad.FirmasServicios;
import com.actaflow.backend.soporte.DatosPrueba;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FiltroFirmaBffTest {

    private static final long AHORA = 1_760_000_000L;
    private final Clock reloj = relojFijo(AHORA);
    private final FiltroFirmaBff filtro = new FiltroFirmaBff(
            new FirmasServicios(DatosPrueba.propiedades(URI.create("http://127.0.0.1:9"), 64), reloj),
            DatosPrueba.propiedades(URI.create("http://127.0.0.1:9"), 64), reloj);
    private final FirmaHmac firmaBff = new FirmaHmac(SECRETO_BFF, 300, reloj);

    @Test
    void dejaPasarUnaPeticionFirmadaYElCuerpoSePuedeVolverALeer() throws Exception {
        byte[] cuerpo = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest peticion = peticion("POST", "/api/v1/algo", cuerpo);
        firmar(peticion, "POST", "/api/v1/algo", cuerpo);
        MockFilterChain cadena = new MockFilterChain();

        filtro.doFilter(peticion, new MockHttpServletResponse(), cadena);

        assertNotNull(cadena.getRequest(), "la cadena debio continuar");
        HttpServletRequest recibida = (HttpServletRequest) cadena.getRequest();
        assertArrayEquals(cuerpo, recibida.getInputStream().readAllBytes());
    }

    @Test
    void incluyeLaConsultaEnLaRutaFirmada() throws Exception {
        MockHttpServletRequest peticion = peticion("GET", "/api/v1/algo", new byte[0]);
        peticion.setQueryString("pagina=2");
        firmar(peticion, "GET", "/api/v1/algo?pagina=2", new byte[0]);
        MockFilterChain cadena = new MockFilterChain();

        filtro.doFilter(peticion, new MockHttpServletResponse(), cadena);

        assertNotNull(cadena.getRequest());
    }

    @Test
    void sinFirmaResponde401ConApiErrorYNoContinua() throws Exception {
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        MockFilterChain cadena = new MockFilterChain();

        filtro.doFilter(peticion("GET", "/api/v1/status", new byte[0]), respuesta, cadena);

        assertNull(cadena.getRequest());
        assertEquals(401, respuesta.getStatus());
        assertEquals("application/json", respuesta.getContentType().split(";")[0]);
        String json = respuesta.getContentAsString();
        assertTrue(json.contains("\"codigo\":\"firma_invalida\""), json);
        assertTrue(json.contains("\"ruta\":\"/api/v1/status\""), json);
    }

    @Test
    void unaFirmaDeOtraRutaNoSirve() throws Exception {
        MockHttpServletRequest peticion = peticion("GET", "/api/v1/status", new byte[0]);
        firmar(peticion, "GET", "/api/v1/otra", new byte[0]);
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        filtro.doFilter(peticion, respuesta, new MockFilterChain());

        assertEquals(401, respuesta.getStatus());
    }

    @Test
    void cuerpoMayorAlLimiteResponde413() throws Exception {
        byte[] grande = new byte[65];
        MockHttpServletRequest peticion = peticion("POST", "/api/v1/algo", grande);
        firmar(peticion, "POST", "/api/v1/algo", grande);
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        filtro.doFilter(peticion, respuesta, new MockFilterChain());

        assertEquals(413, respuesta.getStatus());
        assertTrue(respuesta.getContentAsString().contains("cuerpo_demasiado_grande"));
    }

    @Test
    void lasRutasFueraDeApiNoExigenFirma() throws Exception {
        MockFilterChain cadena = new MockFilterChain();

        filtro.doFilter(peticion("GET", "/actuator/health", new byte[0]), new MockHttpServletResponse(), cadena);

        assertNotNull(cadena.getRequest());
    }

    private static MockHttpServletRequest peticion(String metodo, String ruta, byte[] cuerpo) {
        MockHttpServletRequest peticion = new MockHttpServletRequest(metodo, ruta);
        peticion.setContent(cuerpo);
        peticion.setContentType("application/json");
        return peticion;
    }

    private void firmar(MockHttpServletRequest peticion, String metodo, String rutaFirmada, byte[] cuerpo) {
        Map<String, String> cabeceras = firmaBff.cabeceras(metodo, rutaFirmada, cuerpo);
        cabeceras.forEach(peticion::addHeader);
    }
}

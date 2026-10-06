package com.actaflow.backend.compartido.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

import com.actaflow.backend.compartido.config.ActaFlowProperties;
import com.actaflow.backend.compartido.seguridad.FirmaHmac;
import com.actaflow.backend.compartido.seguridad.FirmaInvalidaException;
import com.actaflow.backend.compartido.seguridad.FirmasServicios;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Capa Borde: toda peticion a {@code /api/**} debe venir firmada por el BFF.
 * Lo que no venga firmado se rechaza con 401 antes de llegar a cualquier controlador.
 * Tambien limita el tamano del cuerpo (413).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FiltroFirmaBff extends OncePerRequestFilter {

    public static final String PREFIJO_PROTEGIDO = "/api/";

    private static final Logger LOG = LoggerFactory.getLogger(FiltroFirmaBff.class);

    private final FirmaHmac firma;
    private final long maxBytes;
    private final Clock reloj;

    public FiltroFirmaBff(FirmasServicios firmas, ActaFlowProperties propiedades, Clock reloj) {
        this.firma = firmas.bff();
        this.maxBytes = propiedades.bff().maxBodyBytes();
        this.reloj = reloj;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest peticion) {
        return !peticion.getRequestURI().startsWith(PREFIJO_PROTEGIDO);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta,
                                    FilterChain cadena) throws ServletException, IOException {
        String ruta = peticion.getRequestURI();
        if (peticion.getContentLengthLong() > maxBytes) {
            responder(respuesta, 413, "cuerpo_demasiado_grande", "El cuerpo supera el limite permitido", ruta);
            return;
        }
        byte[] cuerpo;
        try (InputStream entrada = peticion.getInputStream()) {
            cuerpo = entrada.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
        }
        if (cuerpo.length > maxBytes) {
            responder(respuesta, 413, "cuerpo_demasiado_grande", "El cuerpo supera el limite permitido", ruta);
            return;
        }

        String consulta = peticion.getQueryString();
        String rutaCanonica = consulta == null ? ruta : ruta + "?" + consulta;
        try {
            firma.verificar(peticion.getHeader(FirmaHmac.CABECERA_TIMESTAMP),
                    peticion.getHeader(FirmaHmac.CABECERA_FIRMA), peticion.getMethod(), rutaCanonica, cuerpo);
        } catch (FirmaInvalidaException e) {
            LOG.warn("Peticion rechazada por firma: {} {} ({})", peticion.getMethod(), ruta, e.motivo());
            responder(respuesta, 401, "firma_invalida", "Peticion no autorizada", ruta);
            return;
        }
        cadena.doFilter(new PeticionConCuerpo(peticion, cuerpo), respuesta);
    }

    private void responder(HttpServletResponse respuesta, int estado, String codigo, String mensaje, String ruta)
            throws IOException {
        respuesta.setStatus(estado);
        respuesta.setContentType("application/json");
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        respuesta.getWriter().write(ApiError.de(estado, codigo, mensaje, ruta, reloj).comoJson());
    }
}

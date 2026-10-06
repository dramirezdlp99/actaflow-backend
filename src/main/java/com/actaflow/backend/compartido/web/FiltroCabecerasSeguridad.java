package com.actaflow.backend.compartido.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Cabeceras de seguridad en todas las respuestas, incluidos los errores de los filtros. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroCabecerasSeguridad extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta,
                                    FilterChain cadena) throws ServletException, IOException {
        respuesta.setHeader("X-Content-Type-Options", "nosniff");
        respuesta.setHeader("X-Frame-Options", "DENY");
        respuesta.setHeader("Referrer-Policy", "no-referrer");
        respuesta.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        respuesta.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        respuesta.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        respuesta.setHeader("Cache-Control", "no-store");
        cadena.doFilter(peticion, respuesta);
    }
}

package com.actaflow.backend.plataforma.web;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

/** Cabeceras de seguridad en todas las respuestas HTTP (gRPC-Web y chequeo de salud). */
public final class FiltroCabecerasSeguridad implements Filter {

    @Override
    public void doFilter(ServletRequest peticion, ServletResponse respuesta, FilterChain cadena)
            throws IOException, ServletException {
        if (respuesta instanceof HttpServletResponse http) {
            http.setHeader("X-Content-Type-Options", "nosniff");
            http.setHeader("X-Frame-Options", "DENY");
            http.setHeader("Referrer-Policy", "no-referrer");
            http.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
            http.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            http.setHeader("Cross-Origin-Resource-Policy", "same-origin");
            http.setHeader("Cache-Control", "no-store");
        }
        cadena.doFilter(peticion, respuesta);
    }
}

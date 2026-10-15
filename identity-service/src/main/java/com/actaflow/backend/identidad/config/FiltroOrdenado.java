package com.actaflow.backend.identidad.config;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.springframework.core.Ordered;

/**
 * Da orden a un filtro de la plataforma (que no depende de Spring). Spring Boot registra cada bean
 * {@link Filter} sobre todas las rutas y los ordena por {@link Ordered}.
 */
final class FiltroOrdenado implements Filter, Ordered {

    private final Filter filtro;
    private final int orden;

    FiltroOrdenado(Filter filtro, int orden) {
        this.filtro = filtro;
        this.orden = orden;
    }

    @Override
    public void doFilter(ServletRequest peticion, ServletResponse respuesta, FilterChain cadena)
            throws IOException, ServletException {
        filtro.doFilter(peticion, respuesta, cadena);
    }

    @Override
    public int getOrder() {
        return orden;
    }
}

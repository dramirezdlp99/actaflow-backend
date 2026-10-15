package com.actaflow.backend.plataforma.grpcweb;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Filtro de Servlet que entrega las llamadas gRPC-Web a la {@link PasarelaGrpcWeb}.
 *
 * <p>Las rutas gRPC ({@code /paquete.Servicio/Metodo}) no se pueden expresar como patron de Servlet,
 * por eso es un filtro sobre todas las rutas: atiende las peticiones gRPC-Web y deja pasar el resto
 * (por ejemplo {@code /actuator/health}, que Render usa como chequeo de salud).
 */
public final class FiltroGrpcWeb implements Filter {

    private final PasarelaGrpcWeb pasarela;

    public FiltroGrpcWeb(PasarelaGrpcWeb pasarela) {
        this.pasarela = pasarela;
    }

    @Override
    public void doFilter(ServletRequest peticion, ServletResponse respuesta, FilterChain cadena)
            throws IOException, ServletException {
        if (!(peticion instanceof HttpServletRequest http) || !(respuesta instanceof HttpServletResponse salidaHttp)
                || !PasarelaGrpcWeb.esLlamadaGrpcWeb(http.getMethod(), http.getContentType())) {
            cadena.doFilter(peticion, respuesta);
            return;
        }
        Map<String, String> cabeceras = new HashMap<>();
        for (String nombre : Collections.list(http.getHeaderNames())) {
            cabeceras.put(nombre.toLowerCase(Locale.ROOT), http.getHeader(nombre));
        }
        pasarela.atender(http.getRequestURI(), http.getContentType(), cabeceras, http.getInputStream(),
                new PasarelaGrpcWeb.Salida() {
                    @Override
                    public void comenzar(int estadoHttp, String tipoContenido) {
                        salidaHttp.setStatus(estadoHttp);
                        salidaHttp.setContentType(tipoContenido);
                    }

                    @Override
                    public OutputStream cuerpo() throws IOException {
                        return salidaHttp.getOutputStream();
                    }
                });
    }
}

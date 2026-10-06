package com.actaflow.backend.compartido.web;

import java.time.Clock;

/**
 * Formato unico de error de toda la API.
 *
 * @param estado  codigo HTTP
 * @param codigo  identificador estable para el cliente (por ejemplo, {@code firma_invalida})
 * @param mensaje texto legible, sin detalles internos
 * @param ruta    ruta solicitada
 * @param instante marca de tiempo ISO-8601 en UTC
 */
public record ApiError(int estado, String codigo, String mensaje, String ruta, String instante) {

    public static ApiError de(int estado, String codigo, String mensaje, String ruta, Clock reloj) {
        return new ApiError(estado, codigo, mensaje, ruta, reloj.instant().toString());
    }

    /** JSON escrito a mano para los filtros, que responden antes de llegar a Spring MVC. */
    public String comoJson() {
        return "{\"estado\":" + estado
                + ",\"codigo\":\"" + escapar(codigo)
                + "\",\"mensaje\":\"" + escapar(mensaje)
                + "\",\"ruta\":\"" + escapar(ruta)
                + "\",\"instante\":\"" + escapar(instante) + "\"}";
    }

    static String escapar(String texto) {
        if (texto == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(texto.length());
        for (char c : texto.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}

package com.actaflow.backend.compartido.web;

/** Error de negocio que se traduce tal cual a un {@link ApiError}. */
public class ApiException extends RuntimeException {

    private final int estado;
    private final String codigo;

    public ApiException(int estado, String codigo, String mensaje) {
        super(mensaje);
        this.estado = estado;
        this.codigo = codigo;
    }

    public int estado() {
        return estado;
    }

    public String codigo() {
        return codigo;
    }
}

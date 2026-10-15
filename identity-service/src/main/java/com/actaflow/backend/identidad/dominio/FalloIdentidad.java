package com.actaflow.backend.identidad.dominio;

import java.util.List;

/**
 * Error esperado de un caso de uso, con el codigo gRPC que le corresponde. El mensaje es seguro
 * de mostrar: nunca revela si un correo existe ni por que fallo exactamente una credencial.
 */
public final class FalloIdentidad extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Codigos gRPC que usa este servicio. */
    public enum Tipo {
        ARGUMENTO_INVALIDO,
        NO_AUTENTICADO,
        SIN_PERMISO,
        NO_ENCONTRADO,
        YA_EXISTE,
        PRECONDICION
    }

    /** Campo y regla incumplidos, con los mismos nombres del contrato. */
    public record Violacion(String campo, String regla, String descripcion) {
    }

    private final Tipo tipo;
    private final transient List<Violacion> violaciones;

    public FalloIdentidad(Tipo tipo, String mensaje) {
        this(tipo, mensaje, List.of());
    }

    public FalloIdentidad(Tipo tipo, String mensaje, List<Violacion> violaciones) {
        super(mensaje);
        this.tipo = tipo;
        this.violaciones = List.copyOf(violaciones);
    }

    public Tipo tipo() {
        return tipo;
    }

    public List<Violacion> violaciones() {
        return violaciones;
    }

    /** Credenciales, desafio o token no validos: siempre el mismo mensaje. */
    public static FalloIdentidad credenciales() {
        return new FalloIdentidad(Tipo.NO_AUTENTICADO, "Credenciales invalidas");
    }
}

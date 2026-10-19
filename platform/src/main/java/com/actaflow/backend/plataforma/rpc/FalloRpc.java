package com.actaflow.backend.plataforma.rpc;

import java.util.List;

/**
 * Error esperado de un caso de uso, con el codigo gRPC que le corresponde. El mensaje es seguro de
 * mostrar: nunca lleva datos internos (SQL, trazas) ni revela lo que el llamador no puede ver.
 */
public final class FalloRpc extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Codigos gRPC que usan los servicios. */
    public enum Tipo {
        ARGUMENTO_INVALIDO,
        NO_AUTENTICADO,
        SIN_PERMISO,
        NO_ENCONTRADO,
        YA_EXISTE,
        PRECONDICION,
        /** Concurrencia optimista: alguien cambio el recurso despues de que el cliente lo leyera. */
        ABORTADO
    }

    /** Campo y regla incumplidos, con los mismos nombres del contrato. */
    public record Violacion(String campo, String regla, String descripcion) {
    }

    private final Tipo tipo;
    private final transient List<Violacion> violaciones;

    public FalloRpc(Tipo tipo, String mensaje) {
        this(tipo, mensaje, List.of());
    }

    public FalloRpc(Tipo tipo, String mensaje, List<Violacion> violaciones) {
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
    public static FalloRpc credenciales() {
        return new FalloRpc(Tipo.NO_AUTENTICADO, "Credenciales invalidas");
    }
}

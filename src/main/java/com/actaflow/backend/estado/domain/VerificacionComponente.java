package com.actaflow.backend.estado.domain;

/**
 * Resultado de verificar una capa.
 *
 * @param estado     OK, DEGRADADO o CAIDO
 * @param latenciaMs milisegundos que tomo la verificacion
 * @param detalle    explicacion corta y segura de mostrar (sin trazas ni secretos)
 * @param version    version reportada por el componente, si la hay
 */
public record VerificacionComponente(EstadoComponente estado, long latenciaMs, String detalle, String version) {

    public static VerificacionComponente ok(long latenciaMs, String detalle, String version) {
        return new VerificacionComponente(EstadoComponente.OK, latenciaMs, detalle, version);
    }

    public static VerificacionComponente degradado(long latenciaMs, String detalle, String version) {
        return new VerificacionComponente(EstadoComponente.DEGRADADO, latenciaMs, detalle, version);
    }

    public static VerificacionComponente caido(long latenciaMs, String detalle) {
        return new VerificacionComponente(EstadoComponente.CAIDO, latenciaMs, detalle, null);
    }
}

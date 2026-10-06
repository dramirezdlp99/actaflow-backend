package com.actaflow.backend.estado.domain;

import java.time.Instant;

/**
 * Fotografia del estado de las capas que dependen del backend.
 * El sistema solo esta OK si todas sus capas lo estan; si no, esta DEGRADADO.
 */
public record EstadoSistema(
        String version,
        EstadoGeneral estado,
        Instant verificadoEn,
        VerificacionComponente baseDatos,
        VerificacionComponente motorIa) {

    /** Estado agregado del sistema. */
    public enum EstadoGeneral {
        OK,
        DEGRADADO
    }

    public static EstadoSistema de(String version, Instant verificadoEn,
                                   VerificacionComponente baseDatos, VerificacionComponente motorIa) {
        boolean todoOk = baseDatos.estado() == EstadoComponente.OK && motorIa.estado() == EstadoComponente.OK;
        return new EstadoSistema(version, todoOk ? EstadoGeneral.OK : EstadoGeneral.DEGRADADO,
                verificadoEn, baseDatos, motorIa);
    }
}

package com.actaflow.backend.estado.web;

import com.actaflow.backend.estado.domain.EstadoSistema;
import com.actaflow.backend.estado.domain.VerificacionComponente;

/** Contrato JSON de {@code GET /api/v1/status} (lo consume el BFF de Next.js). */
public record RespuestaEstado(
        String servicio,
        String version,
        String estado,
        String verificadoEn,
        Componentes componentes) {

    /** Capas verificadas. */
    public record Componentes(Componente baseDatos, Componente motorIa) {
    }

    /** Estado de una capa. */
    public record Componente(String estado, long latenciaMs, String detalle, String version) {

        static Componente de(VerificacionComponente v) {
            return new Componente(v.estado().name(), v.latenciaMs(), v.detalle(), v.version());
        }
    }

    public static RespuestaEstado de(EstadoSistema estado) {
        return new RespuestaEstado("actaflow-backend", estado.version(), estado.estado().name(),
                estado.verificadoEn().toString(),
                new Componentes(Componente.de(estado.baseDatos()), Componente.de(estado.motorIa())));
    }
}

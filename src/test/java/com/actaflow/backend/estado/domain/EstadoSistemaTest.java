package com.actaflow.backend.estado.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class EstadoSistemaTest {

    private static final Instant AHORA = Instant.parse("2026-10-06T15:00:00Z");
    private static final VerificacionComponente OK = VerificacionComponente.ok(5, "ok", "1");

    @Test
    void elSistemaSoloEstaOkSiTodasLasCapasLoEstan() {
        assertEquals(EstadoSistema.EstadoGeneral.OK, EstadoSistema.de("v", AHORA, OK, OK).estado());
    }

    @Test
    void unaCapaDegradadaOCaidaDegradaElSistema() {
        VerificacionComponente degradado = VerificacionComponente.degradado(5, "sin modelo", "0.1.0");
        VerificacionComponente caido = VerificacionComponente.caido(5, "no responde");
        assertEquals(EstadoSistema.EstadoGeneral.DEGRADADO, EstadoSistema.de("v", AHORA, OK, degradado).estado());
        assertEquals(EstadoSistema.EstadoGeneral.DEGRADADO, EstadoSistema.de("v", AHORA, caido, OK).estado());
    }

    @Test
    void caidoNoReportaVersion() {
        assertEquals(null, VerificacionComponente.caido(1, "x").version());
    }
}

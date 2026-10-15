package com.actaflow.backend.identidad.soporte;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Reloj que las pruebas adelantan a voluntad (pasos TOTP, vencimientos, bloqueos). */
public final class RelojMovil extends Clock {

    private Instant ahora;

    public RelojMovil(Instant inicio) {
        this.ahora = inicio;
    }

    public void avanzar(Duration tiempo) {
        ahora = ahora.plus(tiempo);
    }

    @Override
    public Instant instant() {
        return ahora;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zona) {
        return this;
    }
}

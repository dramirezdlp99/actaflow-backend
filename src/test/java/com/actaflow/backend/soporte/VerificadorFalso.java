package com.actaflow.backend.soporte;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.actaflow.backend.estado.application.VerificadorBaseDatos;
import com.actaflow.backend.estado.application.VerificadorMotorIa;
import com.actaflow.backend.estado.domain.VerificacionComponente;

/** Fake de los dos puertos de verificacion: devuelve un resultado fijo, tarda o falla. */
public final class VerificadorFalso implements VerificadorBaseDatos, VerificadorMotorIa {

    private final VerificacionComponente resultado;
    private final Duration demora;
    private final RuntimeException error;
    private final AtomicInteger llamadas = new AtomicInteger();

    private VerificadorFalso(VerificacionComponente resultado, Duration demora, RuntimeException error) {
        this.resultado = resultado;
        this.demora = demora;
        this.error = error;
    }

    public static VerificadorFalso queResponde(VerificacionComponente resultado) {
        return new VerificadorFalso(resultado, Duration.ZERO, null);
    }

    public static VerificadorFalso queTarda(Duration demora, VerificacionComponente resultado) {
        return new VerificadorFalso(resultado, demora, null);
    }

    public static VerificadorFalso queFalla(RuntimeException error) {
        return new VerificadorFalso(null, Duration.ZERO, error);
    }

    @Override
    public VerificacionComponente verificar() {
        llamadas.incrementAndGet();
        if (!demora.isZero()) {
            try {
                Thread.sleep(demora);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return VerificacionComponente.caido(0, "interrumpido");
            }
        }
        if (error != null) {
            throw error;
        }
        return resultado;
    }

    public int llamadas() {
        return llamadas.get();
    }
}

package com.actaflow.backend.estado.application;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.actaflow.backend.estado.domain.EstadoSistema;
import com.actaflow.backend.estado.domain.VerificacionComponente;

/**
 * Caso de uso: consultar el estado de la base de datos y del motor de IA.
 *
 * <p>Las dos verificaciones corren en paralelo (hilos virtuales) y cada una tiene un tiempo
 * maximo medido desde el inicio, para que una capa dormida no bloquee la respuesta.
 */
public class ConsultarEstadoService {

    private final VerificadorBaseDatos baseDatos;
    private final VerificadorMotorIa motorIa;
    private final Duration timeoutBaseDatos;
    private final Duration timeoutMotor;
    private final String version;
    private final Clock reloj;

    public ConsultarEstadoService(VerificadorBaseDatos baseDatos, VerificadorMotorIa motorIa,
                                  Duration timeoutBaseDatos, Duration timeoutMotor, String version, Clock reloj) {
        this.baseDatos = baseDatos;
        this.motorIa = motorIa;
        this.timeoutBaseDatos = timeoutBaseDatos;
        this.timeoutMotor = timeoutMotor;
        this.version = version;
        this.reloj = reloj;
    }

    public EstadoSistema consultar() {
        ExecutorService ejecutor = Executors.newVirtualThreadPerTaskExecutor();
        long inicio = System.nanoTime();
        try {
            Future<VerificacionComponente> bd = ejecutor.submit(baseDatos::verificar);
            Future<VerificacionComponente> motor = ejecutor.submit(motorIa::verificar);
            VerificacionComponente resultadoBd = esperar(bd, timeoutBaseDatos, inicio);
            VerificacionComponente resultadoMotor = esperar(motor, timeoutMotor, inicio);
            return EstadoSistema.de(version, reloj.instant(), resultadoBd, resultadoMotor);
        } finally {
            // No se usa try-with-resources: close() esperaria a una verificacion colgada.
            ejecutor.shutdownNow();
        }
    }

    private static VerificacionComponente esperar(Future<VerificacionComponente> futuro, Duration limite,
                                                  long inicioNanos) {
        long restanteNanos = limite.toNanos() - (System.nanoTime() - inicioNanos);
        try {
            return futuro.get(Math.max(0, restanteNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            futuro.cancel(true);
            return VerificacionComponente.caido(limite.toMillis(),
                    "Sin respuesta en " + limite.toSeconds() + " s (puede estar despertando)");
        } catch (ExecutionException e) {
            return VerificacionComponente.caido(transcurridoMs(inicioNanos), "Error al verificar");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return VerificacionComponente.caido(transcurridoMs(inicioNanos), "Verificacion interrumpida");
        }
    }

    private static long transcurridoMs(long inicioNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicioNanos);
    }
}

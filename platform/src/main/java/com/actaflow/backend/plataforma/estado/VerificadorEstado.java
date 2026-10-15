package com.actaflow.backend.plataforma.estado;

import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.actaflow.contracts.system.v1.ComponentStatus;
import com.actaflow.contracts.system.v1.HealthState;
import com.actaflow.contracts.system.v1.ServiceStatus;
import com.google.protobuf.Timestamp;

/**
 * Consulta el estado de un servicio: corre todas sus comprobaciones en paralelo (hilos virtuales)
 * y cada una tiene su tiempo maximo medido desde el inicio, asi una dependencia dormida (Neon o un
 * servicio de Render que despierta) no bloquea la respuesta.
 *
 * <p>El servicio esta {@code OK} si todos sus componentes lo estan; si no, {@code DEGRADED}. Nunca
 * {@code DOWN}: si responde, esta vivo.
 */
public final class VerificadorEstado {

    private static final System.Logger LOG = System.getLogger(VerificadorEstado.class.getName());

    private final String servicio;
    private final String version;
    private final List<Comprobacion> comprobaciones;
    private final Clock reloj;

    public VerificadorEstado(String servicio, String version, List<Comprobacion> comprobaciones, Clock reloj) {
        this.servicio = servicio;
        this.version = version;
        this.comprobaciones = List.copyOf(comprobaciones);
        this.reloj = reloj;
    }

    /** Estado actual; {@code served_via} lo completa quien atiende la llamada. */
    public ServiceStatus consultar() {
        ExecutorService ejecutor = Executors.newVirtualThreadPerTaskExecutor();
        long inicio = System.nanoTime();
        try {
            List<Future<ComponentStatus>> futuros = new ArrayList<>();
            for (Comprobacion comprobacion : comprobaciones) {
                futuros.add(ejecutor.submit(comprobacion::comprobar));
            }
            ServiceStatus.Builder estado = ServiceStatus.newBuilder()
                    .setServiceName(servicio)
                    .setVersion(version);
            boolean todoOk = true;
            for (int i = 0; i < comprobaciones.size(); i++) {
                ComponentStatus componente = esperar(comprobaciones.get(i), futuros.get(i), inicio);
                todoOk &= componente.getState() == HealthState.HEALTH_STATE_OK;
                estado.addComponents(componente);
            }
            Instant ahora = reloj.instant();
            return estado
                    .setState(todoOk ? HealthState.HEALTH_STATE_OK : HealthState.HEALTH_STATE_DEGRADED)
                    .setCheckedAt(Timestamp.newBuilder().setSeconds(ahora.getEpochSecond()).setNanos(ahora.getNano()))
                    .build();
        } finally {
            // Sin try-with-resources: close() esperaria a una comprobacion colgada.
            ejecutor.shutdownNow();
        }
    }

    private static ComponentStatus esperar(Comprobacion comprobacion, Future<ComponentStatus> futuro,
                                           long inicioNanos) {
        long restante = comprobacion.limite().toNanos() - (System.nanoTime() - inicioNanos);
        ComponentStatus.Builder resultado;
        try {
            resultado = futuro.get(Math.max(0, restante), TimeUnit.NANOSECONDS).toBuilder();
        } catch (TimeoutException e) {
            futuro.cancel(true);
            resultado = caido("Sin respuesta en " + comprobacion.limite().toSeconds()
                    + " s (puede estar despertando)");
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "Fallo la comprobacion de " + comprobacion.id(), e.getCause());
            resultado = caido("Error al verificar");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resultado = caido("Verificacion interrumpida");
        }
        if (resultado.getLatencyMs() == 0) {
            resultado.setLatencyMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicioNanos));
        }
        if (resultado.getState() == HealthState.HEALTH_STATE_UNSPECIFIED) {
            resultado.setState(HealthState.HEALTH_STATE_DEGRADED);
        }
        return resultado.setId(comprobacion.id()).setKind(comprobacion.tipo()).build();
    }

    private static ComponentStatus.Builder caido(String detalle) {
        return ComponentStatus.newBuilder().setState(HealthState.HEALTH_STATE_DOWN).setDetail(detalle);
    }
}

package com.actaflow.backend.plataforma.estado;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.actaflow.contracts.system.v1.ComponentKind;
import com.actaflow.contracts.system.v1.ComponentStatus;
import com.actaflow.contracts.system.v1.GetServiceStatusRequest;
import com.actaflow.contracts.system.v1.HealthState;
import com.actaflow.contracts.system.v1.ServiceStatus;
import com.actaflow.contracts.system.v1.ServiceStatusServiceGrpc;

import io.grpc.Channel;
import io.grpc.StatusRuntimeException;

/**
 * Comprueba otro microservicio llamando a su {@code ServiceStatusService} por gRPC (nativo o
 * gRPC-Web, segun el canal). Su estado completo viaja anidado en {@code ComponentStatus.nested}.
 */
public final class ComprobacionServicioRemoto implements Comprobacion {

    private final String id;
    private final Channel canal;
    private final Duration limite;

    /**
     * @param id     identificador del componente, por ejemplo {@code ai-engine}
     * @param canal  canal hacia el servicio, ya con la firma HMAC del par
     * @param limite tiempo maximo de la llamada
     */
    public ComprobacionServicioRemoto(String id, Channel canal, Duration limite) {
        this.id = id;
        this.canal = canal;
        this.limite = limite;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ComponentKind tipo() {
        return ComponentKind.COMPONENT_KIND_SERVICE;
    }

    @Override
    public Duration limite() {
        return limite;
    }

    @Override
    public ComponentStatus comprobar() {
        long inicio = System.nanoTime();
        try {
            ServiceStatus remoto = ServiceStatusServiceGrpc.newBlockingStub(canal)
                    .withDeadlineAfter(limite.toMillis(), TimeUnit.MILLISECONDS)
                    .getServiceStatus(GetServiceStatusRequest.getDefaultInstance())
                    .getStatus();
            HealthState estado = remoto.getState() == HealthState.HEALTH_STATE_UNSPECIFIED
                    ? HealthState.HEALTH_STATE_DEGRADED : remoto.getState();
            return ComponentStatus.newBuilder()
                    .setState(estado)
                    .setLatencyMs(Math.max(1, transcurridoMs(inicio)))
                    .setDetail(remoto.getServiceName() + " responde")
                    .setVersion(remoto.getVersion())
                    .setNested(remoto)
                    .build();
        } catch (StatusRuntimeException e) {
            return ComponentStatus.newBuilder()
                    .setState(HealthState.HEALTH_STATE_DOWN)
                    .setLatencyMs(Math.max(1, transcurridoMs(inicio)))
                    .setDetail(explicar(e))
                    .build();
        }
    }

    /** Explicacion corta y segura de mostrar: sin trazas, URLs ni secretos. */
    static String explicar(StatusRuntimeException e) {
        return switch (e.getStatus().getCode()) {
            case UNAVAILABLE -> "No se pudo conectar (puede estar despertando)";
            case DEADLINE_EXCEEDED -> "Sin respuesta a tiempo (puede estar despertando)";
            case UNAUTHENTICATED -> "Firma rechazada: revisa el secreto compartido";
            case UNIMPLEMENTED -> "El servicio no publica ServiceStatusService";
            default -> "Error gRPC " + e.getStatus().getCode();
        };
    }

    private static long transcurridoMs(long inicioNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicioNanos);
    }
}

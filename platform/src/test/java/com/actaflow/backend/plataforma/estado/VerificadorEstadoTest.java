package com.actaflow.backend.plataforma.estado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import com.actaflow.contracts.system.v1.ComponentKind;
import com.actaflow.contracts.system.v1.ComponentStatus;
import com.actaflow.contracts.system.v1.HealthState;
import com.actaflow.contracts.system.v1.ServiceStatus;

import org.junit.jupiter.api.Test;

class VerificadorEstadoTest {

    private static final Clock RELOJ = Clock.fixed(Instant.ofEpochSecond(1_760_000_000L, 5), ZoneOffset.UTC);

    private record Falsa(String id, Duration limite, Supplier<ComponentStatus> accion) implements Comprobacion {
        @Override
        public ComponentKind tipo() {
            return ComponentKind.COMPONENT_KIND_SERVICE;
        }

        @Override
        public ComponentStatus comprobar() {
            return accion.get();
        }
    }

    private static ComponentStatus con(HealthState estado) {
        return ComponentStatus.newBuilder().setState(estado).setDetail("detalle").build();
    }

    private static Supplier<ComponentStatus> tardando(Duration espera) {
        return () -> {
            try {
                Thread.sleep(espera);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return con(HealthState.HEALTH_STATE_OK);
        };
    }

    private static ServiceStatus consultar(Comprobacion... comprobaciones) {
        return new VerificadorEstado("identity-service", "0.2.0", List.of(comprobaciones), RELOJ).consultar();
    }

    @Test
    void todoOkEsOkYCompletaIdTipoYHora() {
        ServiceStatus estado = consultar(new Falsa("database", Duration.ofSeconds(1),
                () -> con(HealthState.HEALTH_STATE_OK)));
        assertEquals("identity-service", estado.getServiceName());
        assertEquals("0.2.0", estado.getVersion());
        assertEquals(HealthState.HEALTH_STATE_OK, estado.getState());
        assertEquals(1_760_000_000L, estado.getCheckedAt().getSeconds());
        assertEquals(5, estado.getCheckedAt().getNanos());
        ComponentStatus componente = estado.getComponents(0);
        assertEquals("database", componente.getId());
        assertEquals(ComponentKind.COMPONENT_KIND_SERVICE, componente.getKind());
    }

    @Test
    void unComponenteNoOkDegradaElServicio() {
        ServiceStatus estado = consultar(
                new Falsa("a", Duration.ofSeconds(1), () -> con(HealthState.HEALTH_STATE_OK)),
                new Falsa("b", Duration.ofSeconds(1), () -> con(HealthState.HEALTH_STATE_DOWN)));
        assertEquals(HealthState.HEALTH_STATE_DEGRADED, estado.getState());
        assertEquals(List.of("a", "b"), estado.getComponentsList().stream().map(ComponentStatus::getId).toList());
    }

    @Test
    void lasComprobacionesCorrenEnParaleloYRespetanSuLimite() {
        long inicio = System.nanoTime();
        ServiceStatus estado = consultar(
                new Falsa("lenta", Duration.ofMillis(300), tardando(Duration.ofSeconds(5))),
                new Falsa("media", Duration.ofSeconds(2), tardando(Duration.ofMillis(400))));
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        assertTrue(ms < 1500, "tardo " + ms + " ms");
        assertEquals(HealthState.HEALTH_STATE_DOWN, estado.getComponents(0).getState());
        assertTrue(estado.getComponents(0).getDetail().contains("puede estar despertando"));
        assertEquals(HealthState.HEALTH_STATE_OK, estado.getComponents(1).getState());
        assertTrue(estado.getComponents(1).getLatencyMs() >= 400);
    }

    @Test
    void unaExcepcionEsDownSinFiltrarSuMensaje() {
        ServiceStatus estado = consultar(new Falsa("x", Duration.ofSeconds(1), () -> {
            throw new IllegalStateException("password=secreta");
        }));
        assertEquals(HealthState.HEALTH_STATE_DOWN, estado.getComponents(0).getState());
        assertEquals("Error al verificar", estado.getComponents(0).getDetail());
    }

    @Test
    void unEstadoSinEspecificarCuentaComoDegradado() {
        ServiceStatus estado = consultar(new Falsa("x", Duration.ofSeconds(1), ComponentStatus::getDefaultInstance));
        assertEquals(HealthState.HEALTH_STATE_DEGRADED, estado.getComponents(0).getState());
    }
}

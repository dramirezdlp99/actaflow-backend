package com.actaflow.backend.expedientes.config;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.actaflow.backend.expedientes.aplicacion.ColaTrabajos;
import com.actaflow.backend.expedientes.aplicacion.ServicioAudiencias;
import com.actaflow.backend.expedientes.grpc.ServicioAudienciasGrpc;
import com.actaflow.backend.expedientes.grpc.ServicioClavesProcesadorGrpc;
import com.actaflow.backend.expedientes.motor.MotorGrpc;
import com.actaflow.backend.expedientes.seguridad.InterceptorSesion;
import com.actaflow.backend.plataforma.grpc.ClienteGrpc;
import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.contracts.crypto.v1.GetProcessorKeysRequest;
import com.actaflow.contracts.crypto.v1.ProcessorKeyServiceGrpc;

import io.grpc.BindableService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Audiencias: el servicio del contrato (con sesion) y el worker que transcribe los segmentos y
 * extrae las entidades con el motor de IA. Un solo hilo de trabajo: Render Free tiene una CPU y el
 * motor transcribe de a una intervencion.
 */
@Configuration
public class AudienciasConfig {

    /** Plazo de una llamada al motor: despertar en Render Free + cargar el modelo + 30 s de audio. */
    private static final Duration PLAZO_MOTOR = Duration.ofSeconds(180);
    private static final Duration CADA = Duration.ofSeconds(2);
    private static final Duration FLUJO_MAXIMO = Duration.ofSeconds(50);
    /** Por debajo de los 60 s de la pasarela, aun si el motor esta despertando. */
    private static final Duration PLAZO_CLAVE_MOTOR = Duration.ofSeconds(45);

    @Bean
    public ServicioAudiencias servicioAudiencias(Bd bd, Clock reloj) {
        return new ServicioAudiencias(bd, reloj);
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService planificadorAudiencias() {
        return Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("audiencias-flujo-", 0).daemon()
                .factory());
    }

    @Bean
    public BindableService servicioAudienciasGrpc(ServicioAudiencias servicio,
                                                  @Qualifier("planificadorAudiencias") ScheduledExecutorService plan,
                                                  Clock reloj, InterceptorSesion sesion) {
        // El flujo en vivo se cierra solo a los 50 s: la pasarela gRPC-Web corta toda llamada a los 60 s
        // (actaflow.grpc.web-deadline). El cliente se reconecta desde el ultimo evento y no pierde nada.
        return CasosConfig.conSesion(new ServicioAudienciasGrpc(servicio, plan, reloj, Duration.ofSeconds(1),
                Duration.ofSeconds(15), FLUJO_MAXIMO), sesion);
    }

    /** Clave publica del motor para el navegador (con sesion), pedida al motor y guardada 10 minutos. */
    @Bean
    public BindableService servicioClavesProcesadorGrpc(@Qualifier("clienteMotor") ClienteGrpc clienteMotor,
                                                        Clock reloj, InterceptorSesion sesion) {
        return CasosConfig.conSesion(new ServicioClavesProcesadorGrpc(
                () -> ProcessorKeyServiceGrpc.newBlockingStub(clienteMotor.canal())
                        .withDeadlineAfter(PLAZO_CLAVE_MOTOR.toMillis(), TimeUnit.MILLISECONDS)
                        .getProcessorKeys(GetProcessorKeysRequest.getDefaultInstance()),
                reloj, Duration.ofMinutes(10)), sesion);
    }

    @Bean
    public ColaTrabajos colaTrabajos(Bd bd, Clock reloj, @Qualifier("clienteMotor") ClienteGrpc clienteMotor) {
        return new ColaTrabajos(bd, reloj, new MotorGrpc(clienteMotor, PLAZO_MOTOR));
    }

    @Bean
    public SmartLifecycle trabajadorAudiencias(ColaTrabajos cola) {
        return new Trabajador(cola);
    }

    /** Revisa la cola cada 2 s y procesa lo que haya, de a un trabajo. */
    static final class Trabajador implements SmartLifecycle {

        private static final System.Logger LOG = System.getLogger(Trabajador.class.getName());

        private final ColaTrabajos cola;
        private ScheduledExecutorService hilo;

        Trabajador(ColaTrabajos cola) {
            this.cola = cola;
        }

        @Override
        public synchronized void start() {
            hilo = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("audiencias-trabajo").daemon()
                    .factory());
            hilo.scheduleWithFixedDelay(() -> {
                try {
                    cola.procesarPendientes(20);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "La cola de audiencias fallo; se reintenta", e);
                }
            }, CADA.toMillis(), CADA.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public synchronized void stop() {
            if (hilo != null) {
                hilo.shutdownNow();
                hilo = null;
            }
        }

        @Override
        public synchronized boolean isRunning() {
            return hilo != null;
        }
    }
}

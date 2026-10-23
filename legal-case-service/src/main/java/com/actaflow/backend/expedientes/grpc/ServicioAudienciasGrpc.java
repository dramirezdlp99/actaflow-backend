package com.actaflow.backend.expedientes.grpc;

import static com.actaflow.backend.plataforma.rpc.Respuestas.responder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import com.actaflow.backend.expedientes.aplicacion.ServicioAudiencias;
import com.actaflow.backend.expedientes.seguridad.InterceptorSesion;
import com.actaflow.backend.expedientes.seguridad.Usuario;
import com.actaflow.backend.plataforma.rpc.FalloRpc;
import com.actaflow.backend.plataforma.rpc.Respuestas;
import com.actaflow.contracts.hearings.v1.ConfirmEntitiesRequest;
import com.actaflow.contracts.hearings.v1.ConfirmEntitiesResponse;
import com.actaflow.contracts.hearings.v1.EndHearingRequest;
import com.actaflow.contracts.hearings.v1.EndHearingResponse;
import com.actaflow.contracts.hearings.v1.GetEntityExtractionRequest;
import com.actaflow.contracts.hearings.v1.GetEntityExtractionResponse;
import com.actaflow.contracts.hearings.v1.GetHearingRequest;
import com.actaflow.contracts.hearings.v1.GetHearingResponse;
import com.actaflow.contracts.hearings.v1.HearingServiceGrpc;
import com.actaflow.contracts.hearings.v1.ListHearingsRequest;
import com.actaflow.contracts.hearings.v1.ListHearingsResponse;
import com.actaflow.contracts.hearings.v1.ListSegmentsRequest;
import com.actaflow.contracts.hearings.v1.ListSegmentsResponse;
import com.actaflow.contracts.hearings.v1.RequestEntityExtractionRequest;
import com.actaflow.contracts.hearings.v1.RequestEntityExtractionResponse;
import com.actaflow.contracts.hearings.v1.StartHearingRequest;
import com.actaflow.contracts.hearings.v1.StartHearingResponse;
import com.actaflow.contracts.hearings.v1.SubmitAudioSegmentRequest;
import com.actaflow.contracts.hearings.v1.SubmitAudioSegmentResponse;
import com.actaflow.contracts.hearings.v1.WatchHearingEventsRequest;
import com.actaflow.contracts.hearings.v1.WatchHearingEventsResponse;
import com.google.protobuf.Timestamp;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

/**
 * HearingService del contrato. WatchHearingEvents consulta la tabla de eventos cada segundo (en un
 * planificador propio, sin ocupar los hilos de gRPC), manda un latido si no hubo nada en un rato
 * (los proxies cortan las conexiones mudas) y cierra el flujo cuando la audiencia termino y ya se
 * enviaron todos sus eventos, o al cumplir el tiempo maximo: el cliente se reconecta desde el ultimo
 * evento que recibio.
 */
public final class ServicioAudienciasGrpc extends HearingServiceGrpc.HearingServiceImplBase {

    private static final int LOTE = 100;

    private final ServicioAudiencias servicio;
    private final ScheduledExecutorService planificador;
    private final Clock reloj;
    private final Duration intervalo;
    private final Duration latido;
    private final Duration maximo;

    public ServicioAudienciasGrpc(ServicioAudiencias servicio, ScheduledExecutorService planificador, Clock reloj,
                                  Duration intervalo, Duration latido, Duration maximo) {
        this.servicio = servicio;
        this.planificador = planificador;
        this.reloj = reloj;
        this.intervalo = intervalo;
        this.latido = latido;
        this.maximo = maximo;
    }

    @Override
    public void startHearing(StartHearingRequest p, StreamObserver<StartHearingResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("case_id", p.getCaseId()).uuid("idempotency_key", p.getIdempotencyKey()).verificar();
            return servicio.iniciar(InterceptorSesion.actual(), UUID.fromString(p.getCaseId()),
                    UUID.fromString(p.getIdempotencyKey()));
        });
    }

    @Override
    public void endHearing(EndHearingRequest p, StreamObserver<EndHearingResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("hearing_id", p.getHearingId()).verificar();
            return servicio.terminar(InterceptorSesion.actual(), UUID.fromString(p.getHearingId()));
        });
    }

    @Override
    public void getHearing(GetHearingRequest p, StreamObserver<GetHearingResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("hearing_id", p.getHearingId()).verificar();
            return servicio.obtener(InterceptorSesion.actual(), UUID.fromString(p.getHearingId()));
        });
    }

    @Override
    public void listHearings(ListHearingsRequest p, StreamObserver<ListHearingsResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("case_id", p.getCaseId()).verificar();
            return servicio.listar(InterceptorSesion.actual(), UUID.fromString(p.getCaseId()));
        });
    }

    @Override
    public void submitAudioSegment(SubmitAudioSegmentRequest p, StreamObserver<SubmitAudioSegmentResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("hearing_id", p.getHearingId())
                    .uuid("segment_id", p.getSegmentId())
                    .cumple("sequence", "int32.gte", p.getSequence() >= 0, "value must be greater than or equal to 0")
                    .uuid("speaker_participant_id", p.getSpeakerParticipantId())
                    .cumple("duration_ms", "int64.gt_lte", p.getDurationMs() > 0 && p.getDurationMs() <= 30_000,
                            "value must be greater than 0 and less than or equal to 30000")
                    .sobre("audio", p.hasAudio(), p.getAudio())
                    .verificar();
            return servicio.enviarSegmento(InterceptorSesion.actual(), p);
        });
    }

    @Override
    public void listSegments(ListSegmentsRequest p, StreamObserver<ListSegmentsResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("hearing_id", p.getHearingId())
                    .cumple("after_sequence", "int32.gte", p.getAfterSequence() >= -1,
                            "value must be greater than or equal to -1")
                    .verificar();
            return servicio.segmentos(InterceptorSesion.actual(), UUID.fromString(p.getHearingId()),
                    p.getAfterSequence());
        });
    }

    @Override
    public void requestEntityExtraction(RequestEntityExtractionRequest p,
                                        StreamObserver<RequestEntityExtractionResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("hearing_id", p.getHearingId())
                    .uuid("extraction_id", p.getExtractionId())
                    .sobre("input", p.hasInput(), p.getInput())
                    .verificar();
            return servicio.pedirExtraccion(InterceptorSesion.actual(), p);
        });
    }

    @Override
    public void getEntityExtraction(GetEntityExtractionRequest p, StreamObserver<GetEntityExtractionResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("hearing_id", p.getHearingId()).verificar();
            return servicio.ultimaExtraccion(InterceptorSesion.actual(), UUID.fromString(p.getHearingId()));
        });
    }

    @Override
    public void confirmEntities(ConfirmEntitiesRequest p, StreamObserver<ConfirmEntitiesResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("extraction_id", p.getExtractionId())
                    .cumple("expected_revision", "int64.gte", p.getExpectedRevision() >= 0,
                            "value must be greater than or equal to 0")
                    .blob("reviewed", p.hasReviewed(), p.getReviewed())
                    .verificar();
            return servicio.confirmar(InterceptorSesion.actual(), p);
        });
    }

    @Override
    public void watchHearingEvents(WatchHearingEventsRequest p, StreamObserver<WatchHearingEventsResponse> r) {
        UUID audienciaId;
        try {
            new Reglas().uuid("hearing_id", p.getHearingId())
                    .cumple("after_event_sequence", "int64.gte", p.getAfterEventSequence() >= 0,
                            "value must be greater than or equal to 0")
                    .verificar();
            audienciaId = UUID.fromString(p.getHearingId());
            Usuario usuario = InterceptorSesion.actual();
            servicio.exigirAcceso(usuario, audienciaId);
        } catch (FalloRpc fallo) {
            r.onError(Respuestas.error(fallo));
            return;
        } catch (RuntimeException e) {
            r.onError(Status.INTERNAL.withDescription("Error interno").asRuntimeException());
            return;
        }
        new Vigilancia((ServerCallStreamObserver<WatchHearingEventsResponse>) r, audienciaId,
                p.getAfterEventSequence()).empezar();
    }

    /** Un flujo abierto: consulta periodica hasta que la audiencia termina, vence o el cliente se va. */
    private final class Vigilancia implements Runnable {

        private final ServerCallStreamObserver<WatchHearingEventsResponse> salida;
        private final UUID audienciaId;
        private final AtomicReference<ScheduledFuture<?>> tarea = new AtomicReference<>();
        private final long inicio = System.nanoTime();
        private long secuencia;
        private long ultimoEnvio = System.nanoTime();
        /** ReentrantLock y no synchronized: se usa desde hilos virtuales (ver ServicioClavesProcesadorGrpc). */
        private final ReentrantLock candado = new ReentrantLock();
        private volatile boolean cerrada;

        Vigilancia(ServerCallStreamObserver<WatchHearingEventsResponse> salida, UUID audienciaId, long secuencia) {
            this.salida = salida;
            this.audienciaId = audienciaId;
            this.secuencia = secuencia;
        }

        void empezar() {
            candado.lock();
            try {
                salida.setOnCancelHandler(this::cerrar);
                tarea.set(planificador.scheduleWithFixedDelay(this, 0, intervalo.toMillis(), TimeUnit.MILLISECONDS));
            } finally {
                candado.unlock();
            }
        }

        @Override
        public void run() {
            candado.lock();
            try {
                ejecutar();
            } finally {
                candado.unlock();
            }
        }

        private void ejecutar() {
            if (cerrada) {
                return;
            }
            try {
                ServicioAudiencias.Lote lote = servicio.eventos(audienciaId, secuencia, LOTE);
                for (WatchHearingEventsResponse evento : lote.eventos()) {
                    salida.onNext(evento);
                    secuencia = evento.getEventSequence();
                    ultimoEnvio = System.nanoTime();
                }
                boolean vencida = System.nanoTime() - inicio >= maximo.toNanos();
                if ((lote.terminada() && lote.eventos().size() < LOTE) || vencida) {
                    cerrar();
                    salida.onCompleted();
                    return;
                }
                if (System.nanoTime() - ultimoEnvio >= latido.toNanos()) {
                    Instant ahora = reloj.instant();
                    salida.onNext(WatchHearingEventsResponse.newBuilder().setEventSequence(secuencia)
                            .setHeartbeat(Timestamp.newBuilder().setSeconds(ahora.getEpochSecond())
                                    .setNanos(ahora.getNano()))
                            .build());
                    ultimoEnvio = System.nanoTime();
                }
            } catch (RuntimeException e) {
                // Cliente que se fue a mitad de un envio, o la base no responde: se corta el flujo.
                cerrar();
                try {
                    salida.onError(Status.UNAVAILABLE.withDescription("Flujo interrumpido").asRuntimeException());
                } catch (RuntimeException ignorada) {
                    // El flujo ya estaba cerrado.
                }
            }
        }

        /** Sin esperar a que termine una consulta en curso: la tarea revisa `cerrada` antes de enviar. */
        private void cerrar() {
            cerrada = true;
            ScheduledFuture<?> t = tarea.get();
            if (t != null) {
                t.cancel(false);
            }
        }
    }
}

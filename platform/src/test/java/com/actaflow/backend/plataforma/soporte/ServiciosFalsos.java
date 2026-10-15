package com.actaflow.backend.plataforma.soporte;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.actaflow.backend.plataforma.grpc.Transporte;
import com.actaflow.contracts.common.v1.FieldViolation;
import com.actaflow.contracts.common.v1.FieldViolations;
import com.actaflow.contracts.engine.v1.StreamTranscriptionRequest;
import com.actaflow.contracts.engine.v1.StreamTranscriptionResponse;
import com.actaflow.contracts.engine.v1.TranscriptionServiceGrpc;
import com.actaflow.contracts.jobs.v1.GetJobRequest;
import com.actaflow.contracts.jobs.v1.GetJobResponse;
import com.actaflow.contracts.jobs.v1.Job;
import com.actaflow.contracts.jobs.v1.JobServiceGrpc;
import com.actaflow.contracts.jobs.v1.JobState;
import com.actaflow.contracts.jobs.v1.WatchJobRequest;
import com.actaflow.contracts.jobs.v1.WatchJobResponse;
import com.actaflow.contracts.system.v1.GetServiceStatusRequest;
import com.actaflow.contracts.system.v1.GetServiceStatusResponse;
import com.actaflow.contracts.system.v1.HealthState;
import com.actaflow.contracts.system.v1.ServiceStatus;
import com.actaflow.contracts.system.v1.ServiceStatusServiceGrpc;
import com.google.protobuf.Any;
import com.google.rpc.Code;

import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;

/** Implementaciones falsas de servicios reales del contrato, para probar la infraestructura. */
public final class ServiciosFalsos {

    /** Secreto falso y publico con el que se calcularon los vectores compartidos por los tres repos. */
    public static final String SECRETO = "secreto-de-prueba-publico-solo-para-tests-0123456789";

    private ServiciosFalsos() {
    }

    /** Estado: responde OK e informa por que transporte llego la llamada. */
    public static final class Estado extends ServiceStatusServiceGrpc.ServiceStatusServiceImplBase {

        private final AtomicInteger llamadas = new AtomicInteger();

        @Override
        public void getServiceStatus(GetServiceStatusRequest peticion,
                                     StreamObserver<GetServiceStatusResponse> respuesta) {
            llamadas.incrementAndGet();
            respuesta.onNext(GetServiceStatusResponse.newBuilder().setStatus(ServiceStatus.newBuilder()
                    .setServiceName("servicio-falso")
                    .setState(HealthState.HEALTH_STATE_OK)
                    .setServedVia(Transporte.actual().contrato())).build());
            respuesta.onCompleted();
        }

        public int llamadas() {
            return llamadas.get();
        }
    }

    /**
     * Trabajos: GetJob falla con detalles tipados (para probar que viajan) y WatchJob emite tres
     * estados (streaming del servidor). Si el id es "lento", tarda dos segundos.
     */
    public static final class Trabajos extends JobServiceGrpc.JobServiceImplBase {

        @Override
        public void getJob(GetJobRequest peticion, StreamObserver<GetJobResponse> respuesta) {
            if (peticion.getJobId().equals("lento")) {
                dormir(Duration.ofSeconds(2));
            }
            FieldViolations violaciones = FieldViolations.newBuilder().addViolations(FieldViolation.newBuilder()
                    .setField("job_id").setRule("string.uuid").setDescription("value must be a valid UUID")).build();
            com.google.rpc.Status estado = com.google.rpc.Status.newBuilder()
                    .setCode(Code.INVALID_ARGUMENT_VALUE)
                    .setMessage("La peticion incumple el contrato: ñandú 100%")
                    .addDetails(Any.pack(violaciones))
                    .build();
            respuesta.onError(StatusProto.toStatusRuntimeException(estado));
        }

        @Override
        public void watchJob(WatchJobRequest peticion, StreamObserver<WatchJobResponse> respuesta) {
            for (JobState estado : new JobState[] {JobState.JOB_STATE_QUEUED, JobState.JOB_STATE_RUNNING,
                    JobState.JOB_STATE_DONE}) {
                respuesta.onNext(WatchJobResponse.newBuilder()
                        .setJob(Job.newBuilder().setId(peticion.getJobId()).setState(estado)).build());
            }
            respuesta.onCompleted();
        }
    }

    /** Transcripcion en vivo: devuelve un resultado por cada mensaje (streaming bidireccional). */
    public static final class TranscripcionEnVivo extends TranscriptionServiceGrpc.TranscriptionServiceImplBase {

        @Override
        public StreamObserver<StreamTranscriptionRequest> streamTranscription(
                StreamObserver<StreamTranscriptionResponse> respuesta) {
            return new StreamObserver<>() {
                @Override
                public void onNext(StreamTranscriptionRequest peticion) {
                    respuesta.onNext(StreamTranscriptionResponse.newBuilder().setFinal(false).build());
                }

                @Override
                public void onError(Throwable error) {
                    respuesta.onError(Status.CANCELLED.asRuntimeException());
                }

                @Override
                public void onCompleted() {
                    respuesta.onNext(StreamTranscriptionResponse.newBuilder().setFinal(true).build());
                    respuesta.onCompleted();
                }
            };
        }
    }

    private static void dormir(Duration duracion) {
        try {
            Thread.sleep(duracion);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

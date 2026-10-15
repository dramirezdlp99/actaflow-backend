package com.actaflow.backend.plataforma.grpc;

import static com.actaflow.backend.plataforma.soporte.ServiciosFalsos.SECRETO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.backend.plataforma.soporte.ServiciosFalsos;
import com.actaflow.contracts.engine.v1.StreamTranscriptionRequest;
import com.actaflow.contracts.engine.v1.StreamTranscriptionResponse;
import com.actaflow.contracts.engine.v1.TranscriptionServiceGrpc;
import com.actaflow.contracts.jobs.v1.JobServiceGrpc;
import com.actaflow.contracts.jobs.v1.JobState;
import com.actaflow.contracts.jobs.v1.WatchJobRequest;
import com.actaflow.contracts.jobs.v1.WatchJobResponse;
import com.actaflow.contracts.system.v1.GetServiceStatusRequest;
import com.actaflow.contracts.system.v1.ServiceStatusServiceGrpc;
import com.actaflow.contracts.system.v1.Transport;

import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServidorGrpcTest {

    private final FirmaHmac firma = new FirmaHmac(SECRETO, 300, Clock.systemUTC());
    private final ServiciosFalsos.Estado estado = new ServiciosFalsos.Estado();
    private ServidorGrpc servidor;

    @BeforeEach
    void iniciar() {
        servidor = new ServidorGrpc(List.of(estado, new ServiciosFalsos.Trabajos(),
                new ServiciosFalsos.TranscripcionEnVivo()), firma, -1, 1 << 20);
        servidor.iniciar();
    }

    @AfterEach
    void apagar() {
        servidor.close();
    }

    private Channel firmado(FirmaHmac f) {
        return ClientInterceptors.intercept(servidor.canalEnMemoria(), new InterceptorClienteFirma(f));
    }

    @Test
    void unaLlamadaFirmadaLlegaAlServicioYSabeQueVinoPorGrpcNativo() {
        var respuesta = ServiceStatusServiceGrpc.newBlockingStub(firmado(firma))
                .getServiceStatus(GetServiceStatusRequest.getDefaultInstance());
        assertEquals("servicio-falso", respuesta.getStatus().getServiceName());
        assertEquals(Transport.TRANSPORT_GRPC, respuesta.getStatus().getServedVia());
        assertEquals(1, estado.llamadas());
    }

    @Test
    void sinFirmaOConOtroSecretoSeRechazaYElServicioNoSeEjecuta() {
        FirmaHmac otra = new FirmaHmac("otro-secreto-distinto-de-al-menos-32-caracteres", 300, Clock.systemUTC());
        for (Channel canal : new Channel[] {servidor.canalEnMemoria(), firmado(otra)}) {
            StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                    () -> ServiceStatusServiceGrpc.newBlockingStub(canal)
                            .getServiceStatus(GetServiceStatusRequest.getDefaultInstance()));
            assertEquals(Status.Code.UNAUTHENTICATED, e.getStatus().getCode());
            assertEquals("Llamada no autorizada", e.getStatus().getDescription());
        }
        assertEquals(0, estado.llamadas());
    }

    @Test
    void laFirmaDeOtroMensajeNoSirve() {
        // Metadatos firmados para un cuerpo distinto del que realmente se envia.
        Metadata ajena = firma.metadatos("actaflow.system.v1.ServiceStatusService/GetServiceStatus",
                new byte[] {0x0a, 0x01, 0x01});
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> ServiceStatusServiceGrpc.newBlockingStub(servidor.canalEnMemoria())
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(ajena))
                        .getServiceStatus(GetServiceStatusRequest.getDefaultInstance()));
        assertEquals(Status.Code.UNAUTHENTICATED, e.getStatus().getCode());
    }

    @Test
    void elClienteNoPuedeFingirQueLlegoPorGrpcWeb() {
        Metadata transporte = new Metadata();
        transporte.put(Metadatos.TRANSPORTE, "grpc");
        var respuesta = ServiceStatusServiceGrpc.newBlockingStub(firmado(firma))
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(transporte))
                .getServiceStatus(GetServiceStatusRequest.getDefaultInstance());
        assertEquals(Transport.TRANSPORT_GRPC, respuesta.getStatus().getServedVia());
    }

    @Test
    void streamingDelServidorFirmado() {
        Iterator<WatchJobResponse> flujo = JobServiceGrpc.newBlockingStub(firmado(firma))
                .watchJob(WatchJobRequest.newBuilder().setJobId("t-1").build());
        List<JobState> estados = new ArrayList<>();
        flujo.forEachRemaining(r -> estados.add(r.getJob().getState()));
        assertEquals(List.of(JobState.JOB_STATE_QUEUED, JobState.JOB_STATE_RUNNING, JobState.JOB_STATE_DONE), estados);
    }

    @Test
    void streamingBidireccionalFirmaElInicioDeLaLlamada() throws Exception {
        CompletableFuture<List<Boolean>> resultado = new CompletableFuture<>();
        List<Boolean> finales = new ArrayList<>();
        StreamObserver<StreamTranscriptionRequest> envio = TranscriptionServiceGrpc.newStub(firmado(firma))
                .streamTranscription(new StreamObserver<>() {
                    @Override
                    public void onNext(StreamTranscriptionResponse r) {
                        finales.add(r.getFinal());
                    }

                    @Override
                    public void onError(Throwable t) {
                        resultado.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                        resultado.complete(finales);
                    }
                });
        envio.onNext(StreamTranscriptionRequest.getDefaultInstance());
        envio.onNext(StreamTranscriptionRequest.getDefaultInstance());
        envio.onCompleted();
        assertEquals(List.of(false, false, true), resultado.get(5, TimeUnit.SECONDS));
    }

    @Test
    void streamingBidireccionalSinFirmaSeRechaza() {
        CompletableFuture<Throwable> error = new CompletableFuture<>();
        TranscriptionServiceGrpc.newStub(servidor.canalEnMemoria()).streamTranscription(new StreamObserver<>() {
            @Override
            public void onNext(StreamTranscriptionResponse r) {
            }

            @Override
            public void onError(Throwable t) {
                error.complete(t);
            }

            @Override
            public void onCompleted() {
                error.complete(null);
            }
        }).onCompleted();
        assertEquals(Status.Code.UNAUTHENTICATED, Status.fromThrowable(error.join()).getCode());
    }

    @Test
    void conoceElTipoDeCadaMetodoPublicado() {
        var tipos = servidor.tiposDeMetodo();
        assertEquals(MethodDescriptor.MethodType.UNARY,
                tipos.get("actaflow.system.v1.ServiceStatusService/GetServiceStatus"));
        assertEquals(MethodDescriptor.MethodType.SERVER_STREAMING, tipos.get("actaflow.jobs.v1.JobService/WatchJob"));
        assertEquals(MethodDescriptor.MethodType.BIDI_STREAMING,
                tipos.get("actaflow.engine.v1.TranscriptionService/StreamTranscription"));
        assertTrue(tipos.containsKey("actaflow.engine.v1.TranscriptionService/TranscribeSegment"));
    }

    @Test
    void conPuertoNegativoNoHayServidorNativo() {
        assertEquals(-1, servidor.puertoNativo());
    }
}

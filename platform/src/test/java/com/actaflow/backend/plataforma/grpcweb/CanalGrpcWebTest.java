package com.actaflow.backend.plataforma.grpcweb;

import static com.actaflow.backend.plataforma.soporte.ServiciosFalsos.SECRETO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.actaflow.backend.plataforma.grpc.InterceptorClienteFirma;
import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.backend.plataforma.soporte.ServiciosFalsos;
import com.actaflow.backend.plataforma.soporte.ServidorHttpGrpcWeb;
import com.actaflow.contracts.common.v1.FieldViolations;
import com.actaflow.contracts.engine.v1.StreamTranscriptionResponse;
import com.actaflow.contracts.engine.v1.TranscriptionServiceGrpc;
import com.actaflow.contracts.jobs.v1.GetJobRequest;
import com.actaflow.contracts.jobs.v1.JobServiceGrpc;
import com.actaflow.contracts.jobs.v1.JobState;
import com.actaflow.contracts.jobs.v1.WatchJobRequest;
import com.actaflow.contracts.system.v1.GetServiceStatusRequest;
import com.actaflow.contracts.system.v1.GetServiceStatusResponse;
import com.actaflow.contracts.system.v1.ServiceStatusServiceGrpc;
import com.actaflow.contracts.system.v1.Transport;

import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CanalGrpcWebTest {

    private final FirmaHmac firma = new FirmaHmac(SECRETO, 300, Clock.systemUTC());
    private ServidorGrpc servidor;
    private ServidorHttpGrpcWeb http;
    private CanalGrpcWeb canal;

    @BeforeEach
    void iniciar() {
        servidor = new ServidorGrpc(List.of(new ServiciosFalsos.Estado(), new ServiciosFalsos.Trabajos(),
                new ServiciosFalsos.TranscripcionEnVivo()), firma, -1, 1 << 20);
        servidor.iniciar();
        http = ServidorHttpGrpcWeb.iniciar(new PasarelaGrpcWeb(servidor.canalEnMemoria(), servidor.tiposDeMetodo(),
                1 << 20, Duration.ofSeconds(30)));
        canal = nuevoCanal(http.url());
    }

    @AfterEach
    void apagar() {
        canal.close();
        http.close();
        servidor.close();
    }

    private static CanalGrpcWeb nuevoCanal(URI url) {
        return new CanalGrpcWeb(url, Duration.ofSeconds(2), Duration.ofSeconds(10), 1 << 20);
    }

    private Channel firmado(Channel base) {
        return ClientInterceptors.intercept(base, new InterceptorClienteFirma(firma));
    }

    @Test
    void elStubGeneradoFuncionaSobreGrpcWebConLaFirmaHmac() {
        GetServiceStatusResponse respuesta = ServiceStatusServiceGrpc.newBlockingStub(firmado(canal))
                .getServiceStatus(GetServiceStatusRequest.getDefaultInstance());
        assertEquals("servicio-falso", respuesta.getStatus().getServiceName());
        assertEquals(Transport.TRANSPORT_GRPC_WEB, respuesta.getStatus().getServedVia());
    }

    @Test
    void sinFirmaElErrorLlegaComoStatus() {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> ServiceStatusServiceGrpc.newBlockingStub(canal)
                        .getServiceStatus(GetServiceStatusRequest.getDefaultInstance()));
        assertEquals(Status.Code.UNAUTHENTICATED, e.getStatus().getCode());
        assertEquals("Llamada no autorizada", e.getStatus().getDescription());
    }

    @Test
    void losDetallesTipadosDelErrorSobrevivenAlViaje() throws Exception {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> JobServiceGrpc.newBlockingStub(firmado(canal)).getJob(GetJobRequest.newBuilder().setJobId("x")
                        .build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
        assertEquals("La peticion incumple el contrato: ñandú 100%", e.getStatus().getDescription());
        com.google.rpc.Status detalle = StatusProto.fromThrowable(e);
        assertEquals("job_id", detalle.getDetails(0).unpack(FieldViolations.class).getViolations(0).getField());
    }

    @Test
    void streamingDelServidorConElStubBloqueante() {
        List<JobState> estados = new ArrayList<>();
        JobServiceGrpc.newBlockingStub(firmado(canal)).watchJob(WatchJobRequest.newBuilder().setJobId("t-1").build())
                .forEachRemaining(r -> estados.add(r.getJob().getState()));
        assertEquals(List.of(JobState.JOB_STATE_QUEUED, JobState.JOB_STATE_RUNNING, JobState.JOB_STATE_DONE), estados);
    }

    @Test
    void elStubAsincronoTambienFunciona() throws Exception {
        CompletableFuture<GetServiceStatusResponse> futura = new CompletableFuture<>();
        ServiceStatusServiceGrpc.newStub(firmado(canal)).getServiceStatus(GetServiceStatusRequest.getDefaultInstance(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(GetServiceStatusResponse r) {
                        futura.complete(r);
                    }

                    @Override
                    public void onError(Throwable t) {
                        futura.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
        assertEquals("servicio-falso", futura.get(5, TimeUnit.SECONDS).getStatus().getServiceName());
    }

    @Test
    void respetaElPlazoDeLaLlamada() {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> JobServiceGrpc.newBlockingStub(firmado(canal)).withDeadlineAfter(300, TimeUnit.MILLISECONDS)
                        .getJob(GetJobRequest.newBuilder().setJobId("lento").build()));
        assertEquals(Status.Code.DEADLINE_EXCEEDED, e.getStatus().getCode());
    }

    @Test
    void unProxyCaidoOUnServicioDormidoEsUnavailable() {
        try (ServidorHttpGrpcWeb caido = ServidorHttpGrpcWeb.queResponde(503);
                CanalGrpcWeb otro = nuevoCanal(caido.url())) {
            StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                    () -> ServiceStatusServiceGrpc.newBlockingStub(otro)
                            .getServiceStatus(GetServiceStatusRequest.getDefaultInstance()));
            assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
        }
    }

    @Test
    void sinNadieEscuchandoEsUnavailable() throws Exception {
        int libre;
        try (ServerSocket s = new ServerSocket(0)) {
            libre = s.getLocalPort();
        }
        try (CanalGrpcWeb otro = nuevoCanal(URI.create("http://127.0.0.1:" + libre))) {
            StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                    () -> ServiceStatusServiceGrpc.newBlockingStub(otro)
                            .getServiceStatus(GetServiceStatusRequest.getDefaultInstance()));
            assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
        }
    }

    @Test
    void elStreamingDelClienteNoExisteEnGrpcWeb() {
        CompletableFuture<Throwable> error = new CompletableFuture<>();
        TranscriptionServiceGrpc.newStub(canal).streamTranscription(new StreamObserver<StreamTranscriptionResponse>() {
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
        });
        assertEquals(Status.Code.UNIMPLEMENTED, Status.fromThrowable(error.join()).getCode());
    }

    @Test
    void mapeaLosEstadosHttpSegunLaEspecificacion() {
        assertEquals(Status.Code.UNAUTHENTICATED, CanalGrpcWeb.estadoDesdeHttp(401).getCode());
        assertEquals(Status.Code.UNIMPLEMENTED, CanalGrpcWeb.estadoDesdeHttp(404).getCode());
        assertEquals(Status.Code.UNAVAILABLE, CanalGrpcWeb.estadoDesdeHttp(502).getCode());
        assertEquals(Status.Code.UNKNOWN, CanalGrpcWeb.estadoDesdeHttp(500).getCode());
    }

    @Test
    void rechazaEsquemasQueNoSonHttp() {
        assertThrows(IllegalArgumentException.class, () -> nuevoCanal(URI.create("file:///etc/passwd")));
        assertTrue(canal.authority().startsWith("127.0.0.1:"));
    }
}

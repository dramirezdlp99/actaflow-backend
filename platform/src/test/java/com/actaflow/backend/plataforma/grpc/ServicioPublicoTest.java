package com.actaflow.backend.plataforma.grpc;

import static com.actaflow.backend.plataforma.soporte.ServiciosFalsos.SECRETO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.util.List;

import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.backend.plataforma.soporte.ServiciosFalsos;
import com.actaflow.contracts.jobs.v1.GetJobRequest;
import com.actaflow.contracts.jobs.v1.JobServiceGrpc;
import com.actaflow.contracts.system.v1.GetServiceStatusRequest;
import com.actaflow.contracts.system.v1.ServiceStatusServiceGrpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import org.junit.jupiter.api.Test;

class ServicioPublicoTest {

    @Test
    void unServicioPublicoNoExigeFirmaYLosDemasSiguenExigiendola() {
        FirmaHmac firma = new FirmaHmac(SECRETO, 300, Clock.systemUTC());
        ServiciosFalsos.Estado estado = new ServiciosFalsos.Estado();
        try (ServidorGrpc servidor = new ServidorGrpc(
                List.of(ServicioPublico.de(estado), new ServiciosFalsos.Trabajos()), firma, -1, 1 << 20)) {
            servidor.iniciar();
            var publico = ServiceStatusServiceGrpc.newBlockingStub(servidor.canalEnMemoria())
                    .getServiceStatus(GetServiceStatusRequest.getDefaultInstance());
            assertEquals("servicio-falso", publico.getStatus().getServiceName());
            assertEquals(1, estado.llamadas());

            StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                    () -> JobServiceGrpc.newBlockingStub(servidor.canalEnMemoria())
                            .getJob(GetJobRequest.newBuilder().setJobId("x").build()));
            assertEquals(Status.Code.UNAUTHENTICATED, e.getStatus().getCode());
        }
    }
}

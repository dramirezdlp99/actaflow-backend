package com.actaflow.backend.plataforma.grpc;

import static com.actaflow.backend.plataforma.soporte.ServiciosFalsos.SECRETO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.backend.plataforma.soporte.ServiciosFalsos;
import com.actaflow.contracts.system.v1.GetServiceStatusRequest;
import com.actaflow.contracts.system.v1.GetServiceStatusResponse;
import com.actaflow.contracts.system.v1.ServiceStatusServiceGrpc;
import com.actaflow.contracts.system.v1.Transport;

import io.grpc.ClientInterceptors;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** gRPC nativo de verdad: HTTP/2 sobre un socket TCP, como en local y en CI. */
class TransporteNativoTest {

    private final FirmaHmac firma = new FirmaHmac(SECRETO, 300, Clock.systemUTC());
    private ServidorGrpc servidor;
    private ManagedChannel canal;

    @BeforeEach
    void iniciar() {
        servidor = new ServidorGrpc(List.of(new ServiciosFalsos.Estado()), firma, 0, 1 << 20);
        servidor.iniciar();
        canal = Grpc.newChannelBuilder("127.0.0.1:" + servidor.puertoNativo(), InsecureChannelCredentials.create())
                .build();
    }

    @AfterEach
    void apagar() throws InterruptedException {
        canal.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        servidor.close();
    }

    @Test
    void conPuertoCeroEligeUnPuertoLibre() {
        assertTrue(servidor.puertoNativo() > 0);
    }

    @Test
    void unaLlamadaFirmadaViajaPorHttp2() {
        GetServiceStatusResponse respuesta = ServiceStatusServiceGrpc
                .newBlockingStub(ClientInterceptors.intercept(canal, new InterceptorClienteFirma(firma)))
                .getServiceStatus(GetServiceStatusRequest.getDefaultInstance());
        assertEquals(Transport.TRANSPORT_GRPC, respuesta.getStatus().getServedVia());
    }

    @Test
    void sinFirmaSeRechazaTambienEnElPuertoNativo() {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> ServiceStatusServiceGrpc.newBlockingStub(canal)
                        .getServiceStatus(GetServiceStatusRequest.getDefaultInstance()));
        assertEquals(Status.Code.UNAUTHENTICATED, e.getStatus().getCode());
    }
}

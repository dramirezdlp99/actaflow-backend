package com.actaflow.backend.expedientes.soporte;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

import com.actaflow.backend.plataforma.grpc.ClienteGrpc;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.contracts.system.v1.GetServiceStatusRequest;
import com.actaflow.contracts.system.v1.ServiceStatus;
import com.actaflow.contracts.system.v1.ServiceStatusServiceGrpc;

/** Hace de BFF: llama al servicio con los stubs generados y la firma del par BFF-expedientes. */
public final class ClienteBff {

    /** El mismo secreto falso y publico de application-test.yaml. */
    public static final String SECRETO = "secreto-de-prueba-publico-solo-para-tests-0123456789";

    private ClienteBff() {
    }

    /**
     * @param transporte    {@code grpc} o {@code grpc-web}
     * @param puertoWeb     puerto web del servicio (gRPC-Web)
     * @param puertoNativo  puerto gRPC nativo del servicio
     */
    public static ServiceStatus estado(String transporte, int puertoWeb, int puertoNativo) {
        try (ClienteGrpc cliente = ClienteGrpc.crear(transporte, "127.0.0.1:" + puertoNativo,
                URI.create("http://127.0.0.1:" + puertoWeb), new FirmaHmac(SECRETO, 300, Clock.systemUTC()),
                Duration.ofSeconds(2), Duration.ofSeconds(20), 1 << 20)) {
            return ServiceStatusServiceGrpc.newBlockingStub(cliente.canal())
                    .getServiceStatus(GetServiceStatusRequest.getDefaultInstance()).getStatus();
        }
    }
}

package com.actaflow.backend.plataforma.estado;

import com.actaflow.backend.plataforma.grpc.Transporte;
import com.actaflow.contracts.system.v1.GetServiceStatusRequest;
import com.actaflow.contracts.system.v1.GetServiceStatusResponse;
import com.actaflow.contracts.system.v1.ServiceStatusServiceGrpc;

import io.grpc.stub.StreamObserver;

/** Implementacion de {@code actaflow.system.v1.ServiceStatusService} comun a los microservicios Java. */
public final class ServicioEstado extends ServiceStatusServiceGrpc.ServiceStatusServiceImplBase {

    private final VerificadorEstado verificador;

    public ServicioEstado(VerificadorEstado verificador) {
        this.verificador = verificador;
    }

    @Override
    public void getServiceStatus(GetServiceStatusRequest peticion,
                                 StreamObserver<GetServiceStatusResponse> respuesta) {
        // El transporte se lee aqui, en el hilo de la llamada, donde vive el contexto gRPC.
        var transporte = Transporte.actual().contrato();
        respuesta.onNext(GetServiceStatusResponse.newBuilder()
                .setStatus(verificador.consultar().toBuilder().setServedVia(transporte))
                .build());
        respuesta.onCompleted();
    }
}

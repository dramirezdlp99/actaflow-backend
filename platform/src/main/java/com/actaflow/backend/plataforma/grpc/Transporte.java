package com.actaflow.backend.plataforma.grpc;

import com.actaflow.contracts.system.v1.Transport;

import io.grpc.Context;

/** Transporte por el que llego la llamada en curso (lo informa ServiceStatus.served_via). */
public enum Transporte {
    /** gRPC nativo sobre HTTP/2. */
    GRPC(Transport.TRANSPORT_GRPC),
    /** gRPC-Web sobre HTTP/1.1, a traves de la pasarela del puerto web. */
    GRPC_WEB(Transport.TRANSPORT_GRPC_WEB);

    /** Clave del contexto gRPC donde el interceptor del servidor deja el transporte. */
    public static final Context.Key<Transporte> CLAVE = Context.keyWithDefault("actaflow-transporte", GRPC);

    private final Transport contrato;

    Transporte(Transport contrato) {
        this.contrato = contrato;
    }

    /** Transporte de la llamada que se esta atendiendo en este hilo. */
    public static Transporte actual() {
        return CLAVE.get();
    }

    /** Valor equivalente en el contrato (actaflow.system.v1.Transport). */
    public Transport contrato() {
        return contrato;
    }
}

package com.actaflow.backend.plataforma.grpc;

import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;

/**
 * Marca un servicio gRPC como publico: {@link ServidorGrpc} no le exige la firma HMAC del par.
 *
 * <p>Solo para datos que son publicos por definicion, como la clave publica que verifica los tokens
 * (el equivalente a un JWKS): no sirve para firmar nada y cualquiera puede conocerla.
 */
public final class ServicioPublico implements BindableService {

    private final BindableService servicio;

    private ServicioPublico(BindableService servicio) {
        this.servicio = servicio;
    }

    public static ServicioPublico de(BindableService servicio) {
        return new ServicioPublico(servicio);
    }

    @Override
    public ServerServiceDefinition bindService() {
        return servicio.bindService();
    }
}

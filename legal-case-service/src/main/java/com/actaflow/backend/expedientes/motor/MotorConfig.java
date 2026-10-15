package com.actaflow.backend.expedientes.motor;

import java.time.Clock;

import com.actaflow.backend.expedientes.config.ExpedientesProperties;
import com.actaflow.backend.plataforma.grpc.ClienteGrpc;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cliente gRPC del motor de IA. Cada llamada va firmada con el secreto del par expedientes-motor
 * (distinto del secreto del BFF). El transporte lo decide ACTAFLOW_ENGINE_TRANSPORT.
 */
@Configuration
public class MotorConfig {

    /** Tamano maximo de una respuesta del motor (entidades extraidas de una transcripcion larga). */
    private static final int MAX_RESPUESTA_BYTES = 8 * 1024 * 1024;

    @Bean(destroyMethod = "close")
    public ClienteGrpc clienteMotor(ExpedientesProperties propiedades, Clock reloj) {
        ExpedientesProperties.Motor motor = propiedades.motor();
        FirmaHmac firma = new FirmaHmac(motor.hmacSecret(), propiedades.hmac().toleranceSeconds(), reloj);
        return ClienteGrpc.crear(motor.transport(), motor.grpcTarget(), motor.url(), firma, motor.connectTimeout(),
                motor.requestTimeout(), MAX_RESPUESTA_BYTES);
    }
}

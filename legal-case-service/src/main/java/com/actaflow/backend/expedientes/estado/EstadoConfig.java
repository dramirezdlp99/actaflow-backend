package com.actaflow.backend.expedientes.estado;

import java.time.Clock;
import java.util.List;

import javax.sql.DataSource;

import com.actaflow.backend.expedientes.config.ExpedientesProperties;
import com.actaflow.backend.plataforma.estado.ComprobacionBaseDatos;
import com.actaflow.backend.plataforma.estado.ComprobacionServicioRemoto;
import com.actaflow.backend.plataforma.estado.ServicioEstado;
import com.actaflow.backend.plataforma.estado.VerificadorEstado;
import com.actaflow.backend.plataforma.grpc.ClienteGrpc;

import io.grpc.BindableService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code actaflow.system.v1.ServiceStatusService} del servicio de expedientes: su base de datos y
 * el motor de IA, comprobados en paralelo. El estado completo del motor viaja anidado.
 */
@Configuration
public class EstadoConfig {

    /** Nombre del servicio en ServiceStatus y en el mapa de servicios. */
    public static final String NOMBRE_SERVICIO = "legal-case-service";
    /** Esquema de PostgreSQL de este servicio (spring.flyway.schemas). */
    public static final String ESQUEMA = "legal_case";
    /** Identificador del motor de IA como componente. */
    public static final String MOTOR = "ai-engine";

    @Bean
    public VerificadorEstado verificadorEstado(DataSource baseDatos, ClienteGrpc clienteMotor,
                                               ExpedientesProperties propiedades, Clock reloj) {
        ExpedientesProperties.Estado estado = propiedades.estado();
        return new VerificadorEstado(NOMBRE_SERVICIO, propiedades.version(), List.of(
                new ComprobacionBaseDatos(baseDatos, ESQUEMA, estado.timeoutBaseDatos()),
                new ComprobacionServicioRemoto(MOTOR, clienteMotor.canal(), estado.timeoutMotor())), reloj);
    }

    @Bean
    public BindableService servicioEstado(VerificadorEstado verificador) {
        return new ServicioEstado(verificador);
    }
}

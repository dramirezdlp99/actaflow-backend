package com.actaflow.backend.estado.infrastructure;

import java.time.Clock;

import javax.sql.DataSource;

import com.actaflow.backend.compartido.config.ActaFlowProperties;
import com.actaflow.backend.compartido.seguridad.FirmasServicios;
import com.actaflow.backend.estado.application.ConsultarEstadoService;
import com.actaflow.backend.estado.application.VerificadorBaseDatos;
import com.actaflow.backend.estado.application.VerificadorMotorIa;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Cableado del modulo de estado: la capa de aplicacion no conoce Spring. */
@Configuration
public class EstadoConfig {

    @Bean
    public VerificadorBaseDatos verificadorBaseDatos(DataSource dataSource) {
        return new JdbcVerificadorBaseDatos(dataSource);
    }

    @Bean
    public VerificadorMotorIa verificadorMotorIa(ActaFlowProperties propiedades, FirmasServicios firmas) {
        ActaFlowProperties.Motor motor = propiedades.motor();
        return new MotorIaHttpCliente(motor.url(), firmas.motor(), motor.connectTimeout(), motor.requestTimeout());
    }

    @Bean
    public ConsultarEstadoService consultarEstadoService(VerificadorBaseDatos baseDatos, VerificadorMotorIa motorIa,
                                                         ActaFlowProperties propiedades, Clock reloj) {
        ActaFlowProperties.Estado estado = propiedades.estado();
        return new ConsultarEstadoService(baseDatos, motorIa, estado.timeoutBaseDatos(), estado.timeoutMotor(),
                propiedades.version(), reloj);
    }
}

package com.actaflow.backend.identidad.estado;

import java.time.Clock;
import java.util.List;

import javax.sql.DataSource;

import com.actaflow.backend.identidad.config.IdentidadProperties;
import com.actaflow.backend.plataforma.estado.ComprobacionBaseDatos;
import com.actaflow.backend.plataforma.estado.ServicioEstado;
import com.actaflow.backend.plataforma.estado.VerificadorEstado;

import io.grpc.BindableService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** {@code actaflow.system.v1.ServiceStatusService} del servicio de identidad: su base de datos. */
@Configuration
public class EstadoConfig {

    /** Nombre del servicio en ServiceStatus y en el mapa de servicios. */
    public static final String NOMBRE_SERVICIO = "identity-service";
    /** Esquema de PostgreSQL de este servicio (spring.flyway.schemas). */
    public static final String ESQUEMA = "identity";

    @Bean
    public VerificadorEstado verificadorEstado(DataSource baseDatos, IdentidadProperties propiedades, Clock reloj) {
        return new VerificadorEstado(NOMBRE_SERVICIO, propiedades.version(),
                List.of(new ComprobacionBaseDatos(baseDatos, ESQUEMA, propiedades.estado().timeoutBaseDatos())),
                reloj);
    }

    @Bean
    public BindableService servicioEstado(VerificadorEstado verificador) {
        return new ServicioEstado(verificador);
    }
}

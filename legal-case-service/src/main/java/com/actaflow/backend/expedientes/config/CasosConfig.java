package com.actaflow.backend.expedientes.config;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.actaflow.backend.expedientes.aplicacion.ServicioCasos;
import com.actaflow.backend.expedientes.aplicacion.ServicioParticipantes;
import com.actaflow.backend.expedientes.grpc.ServicioCasosGrpc;
import com.actaflow.backend.expedientes.grpc.ServicioParticipantesGrpc;
import com.actaflow.backend.expedientes.seguridad.ClavesIdentidad;
import com.actaflow.backend.expedientes.seguridad.InterceptorSesion;
import com.actaflow.backend.plataforma.grpc.ClienteGrpc;
import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.contracts.identity.v1.GetVerificationKeysRequest;
import com.actaflow.contracts.identity.v1.TokenVerificationServiceGrpc;

import io.grpc.BindableService;
import io.grpc.ServerInterceptors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Expedientes y participantes. Cada servicio del contrato exige, ademas de la firma HMAC del BFF,
 * el token de acceso de la persona, verificado localmente con la clave publica de identity.
 */
@Configuration
public class CasosConfig {

    private static final int MAX_RESPUESTA_IDENTIDAD = 64 * 1024;

    @Bean
    public Bd bd(DataSource baseDatos) {
        return new Bd(baseDatos);
    }

    /** Sin secreto: TokenVerificationService es publico (solo entrega claves publicas). */
    @Bean(destroyMethod = "close")
    public ClienteGrpc clienteIdentidad(ExpedientesProperties propiedades) {
        ExpedientesProperties.Identidad identidad = propiedades.identidad();
        return ClienteGrpc.crearPublico(identidad.transport(), identidad.grpcTarget(), identidad.url(),
                identidad.connectTimeout(), identidad.requestTimeout(), MAX_RESPUESTA_IDENTIDAD);
    }

    @Bean
    public ClavesIdentidad clavesIdentidad(@Qualifier("clienteIdentidad") ClienteGrpc clienteIdentidad,
                                           ExpedientesProperties propiedades, Clock reloj) {
        long plazo = propiedades.identidad().requestTimeout().toMillis();
        return new ClavesIdentidad(() -> TokenVerificationServiceGrpc.newBlockingStub(clienteIdentidad.canal())
                .withDeadlineAfter(plazo, TimeUnit.MILLISECONDS)
                .getVerificationKeys(GetVerificationKeysRequest.getDefaultInstance())
                .getKeysList(), reloj);
    }

    @Bean
    public InterceptorSesion interceptorSesion(ClavesIdentidad claves, Clock reloj) {
        return new InterceptorSesion(claves, reloj);
    }

    @Bean
    public BindableService servicioCasosGrpc(Bd bd, Clock reloj, InterceptorSesion sesion) {
        return conSesion(new ServicioCasosGrpc(new ServicioCasos(bd, reloj)), sesion);
    }

    @Bean
    public BindableService servicioParticipantesGrpc(Bd bd, Clock reloj, InterceptorSesion sesion) {
        return conSesion(new ServicioParticipantesGrpc(new ServicioParticipantes(bd, reloj)), sesion);
    }

    static BindableService conSesion(BindableService servicio, InterceptorSesion sesion) {
        return () -> ServerInterceptors.intercept(servicio, sesion);
    }
}

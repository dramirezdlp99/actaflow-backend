package com.actaflow.backend.identidad.config;

import java.lang.System.Logger.Level;
import java.time.Clock;

import javax.sql.DataSource;

import com.actaflow.backend.identidad.aplicacion.Arranque;
import com.actaflow.backend.identidad.aplicacion.ServicioAutenticacion;
import com.actaflow.backend.identidad.aplicacion.ServicioOrganizacion;
import com.actaflow.backend.identidad.dominio.Criptografia;
import com.actaflow.backend.identidad.dominio.FirmaTokens;
import com.actaflow.backend.identidad.grpc.InterceptorSesion;
import com.actaflow.backend.identidad.grpc.ServicioAuthGrpc;
import com.actaflow.backend.identidad.grpc.ServicioClavesGrpc;
import com.actaflow.backend.identidad.grpc.ServicioCuentaGrpc;
import com.actaflow.backend.identidad.grpc.ServicioOrganizacionGrpc;
import com.actaflow.backend.identidad.persistencia.Bd;

import io.grpc.BindableService;
import io.grpc.ServerInterceptors;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Casos de uso y servicios gRPC de identidad. Cada servicio del contrato se publica envuelto en
 * el interceptor de sesion, que lee el token de acceso de la llamada.
 */
@Configuration
public class IdentidadConfig {

    private static final System.Logger LOG = System.getLogger(IdentidadConfig.class.getName());

    @Bean
    public Bd bd(DataSource baseDatos) {
        return new Bd(baseDatos);
    }

    @Bean
    public Criptografia criptografia(IdentidadProperties propiedades) {
        return new Criptografia(propiedades.seguridad().pepper());
    }

    @Bean
    public FirmaTokens firmaTokens(IdentidadProperties propiedades) {
        IdentidadProperties.Seguridad seguridad = propiedades.seguridad();
        return new FirmaTokens(seguridad.tokenSecret(), seguridad.accessTokenTtl());
    }

    @Bean
    public ServicioAutenticacion servicioAutenticacion(Bd bd, Criptografia cripto, FirmaTokens tokens, Clock reloj) {
        return new ServicioAutenticacion(bd, cripto, tokens, reloj, ServicioAutenticacion.Ajustes.porDefecto());
    }

    @Bean
    public ServicioOrganizacion servicioOrganizacion(Bd bd, Criptografia cripto, Clock reloj) {
        return new ServicioOrganizacion(bd, cripto, reloj);
    }

    @Bean
    public InterceptorSesion interceptorSesion(FirmaTokens tokens, Clock reloj) {
        return new InterceptorSesion(tokens, reloj);
    }

    @Bean
    public BindableService servicioAuthGrpc(ServicioAutenticacion servicio, InterceptorSesion sesion) {
        return conSesion(new ServicioAuthGrpc(servicio), sesion);
    }

    @Bean
    public BindableService servicioCuentaGrpc(ServicioAutenticacion servicio, InterceptorSesion sesion) {
        return conSesion(new ServicioCuentaGrpc(servicio), sesion);
    }

    @Bean
    public BindableService servicioOrganizacionGrpc(ServicioOrganizacion servicio, InterceptorSesion sesion) {
        return conSesion(new ServicioOrganizacionGrpc(servicio), sesion);
    }

    @Bean
    public BindableService servicioDirectorioGrpc(ServicioOrganizacion servicio, InterceptorSesion sesion) {
        return conSesion(new ServicioClavesGrpc.Directorio(servicio), sesion);
    }

    @Bean
    public BindableService servicioVerificacionGrpc(FirmaTokens tokens) {
        return new ServicioClavesGrpc.Verificacion(tokens);
    }

    /** Primer arranque: organizacion e invitacion del administrador, si se configuraron. */
    @Bean
    public ApplicationRunner arranqueIdentidad(Bd bd, Criptografia cripto, Clock reloj,
                                               IdentidadProperties propiedades) {
        return argumentos -> {
            IdentidadProperties.Arranque arranque = propiedades.arranque();
            boolean creado = new Arranque(bd, cripto, reloj).ejecutar(arranque.organizationName(),
                    arranque.adminEmail(), arranque.invitationToken());
            if (creado) {
                LOG.log(Level.INFO, "Primer arranque: organizacion e invitacion del administrador creadas");
            }
        };
    }

    static BindableService conSesion(BindableService servicio, InterceptorSesion sesion) {
        return () -> ServerInterceptors.intercept(servicio, sesion);
    }
}

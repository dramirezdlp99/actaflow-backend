package com.actaflow.backend.identidad.config;

import java.time.Clock;
import java.util.List;

import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.backend.plataforma.grpcweb.FiltroGrpcWeb;
import com.actaflow.backend.plataforma.grpcweb.PasarelaGrpcWeb;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.backend.plataforma.web.FiltroCabecerasSeguridad;

import io.grpc.BindableService;

import jakarta.servlet.Filter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Cableado gRPC del servicio de identidad.
 *
 * <p>Cada {@link BindableService} del contexto se publica en el servidor gRPC, con la firma HMAC del
 * BFF obligatoria. El mismo servidor atiende dos transportes: gRPC nativo (HTTP/2, puerto propio) y
 * gRPC-Web (HTTP/1.1, puerto web, a traves de la pasarela).
 */
@Configuration
public class GrpcConfig {

    /** Reloj inyectable: los tests de firmas y ventanas de tiempo usan uno fijo. */
    @Bean
    public Clock reloj() {
        return Clock.systemUTC();
    }

    /** Verifica la firma del BFF en cada llamada entrante. */
    @Bean
    public FirmaHmac firmaBff(IdentidadProperties propiedades, Clock reloj) {
        return new FirmaHmac(propiedades.bff().hmacSecret(), propiedades.hmac().toleranceSeconds(), reloj);
    }

    @Bean(initMethod = "iniciar", destroyMethod = "close")
    public ServidorGrpc servidorGrpc(List<BindableService> servicios, FirmaHmac firmaBff,
                                     IdentidadProperties propiedades) {
        IdentidadProperties.Grpc grpc = propiedades.grpc();
        return new ServidorGrpc(servicios, firmaBff, grpc.nativePort(), grpc.maxMessageBytes());
    }

    /** Primero las cabeceras de seguridad, para que tambien las lleven las respuestas gRPC-Web. */
    @Bean
    public Filter filtroCabecerasSeguridad() {
        return new FiltroOrdenado(new FiltroCabecerasSeguridad(), Ordered.HIGHEST_PRECEDENCE);
    }

    @Bean
    public Filter filtroGrpcWeb(ServidorGrpc servidorGrpc, IdentidadProperties propiedades) {
        IdentidadProperties.Grpc grpc = propiedades.grpc();
        PasarelaGrpcWeb pasarela = new PasarelaGrpcWeb(servidorGrpc.canalEnMemoria(), servidorGrpc.tiposDeMetodo(),
                grpc.maxMessageBytes(), grpc.webDeadline());
        return new FiltroOrdenado(new FiltroGrpcWeb(pasarela), Ordered.HIGHEST_PRECEDENCE + 10);
    }
}

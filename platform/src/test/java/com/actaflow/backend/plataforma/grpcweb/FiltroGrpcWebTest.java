package com.actaflow.backend.plataforma.grpcweb;

import static com.actaflow.backend.plataforma.soporte.ServiciosFalsos.SECRETO;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import com.actaflow.backend.plataforma.grpc.InterceptorClienteFirma;
import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.backend.plataforma.soporte.ServiciosFalsos;
import com.actaflow.backend.plataforma.web.FiltroCabecerasSeguridad;
import com.actaflow.contracts.system.v1.GetServiceStatusRequest;
import com.actaflow.contracts.system.v1.ServiceStatusServiceGrpc;
import com.actaflow.contracts.system.v1.Transport;

import io.grpc.ClientInterceptors;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Los filtros dentro de un Tomcat embebido real, el mismo contenedor que usa Spring Boot. */
class FiltroGrpcWebTest {

    private ServidorGrpc servidor;
    private Tomcat tomcat;
    private Path base;
    private URI url;

    /** Hace de DispatcherServlet: atiende todo lo que los filtros dejan pasar. */
    private static final class Resto extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest peticion, HttpServletResponse respuesta) throws IOException {
            if ("/actuator/health".equals(peticion.getRequestURI())) {
                respuesta.setContentType("application/json");
                respuesta.getWriter().write("{\"status\":\"UP\"}");
            } else {
                respuesta.sendError(404);
            }
        }
    }

    @BeforeEach
    void iniciar() throws Exception {
        FirmaHmac firma = new FirmaHmac(SECRETO, 300, Clock.systemUTC());
        servidor = new ServidorGrpc(List.of(new ServiciosFalsos.Estado()), firma, -1, 1 << 20);
        servidor.iniciar();
        PasarelaGrpcWeb pasarela = new PasarelaGrpcWeb(servidor.canalEnMemoria(), servidor.tiposDeMetodo(), 1 << 20,
                Duration.ofSeconds(30));

        base = Files.createTempDirectory("tomcat-prueba");
        tomcat = new Tomcat();
        tomcat.setBaseDir(base.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context contexto = tomcat.addContext("", base.toString());
        registrar(contexto, "cabeceras", new FiltroCabecerasSeguridad());
        registrar(contexto, "grpcWeb", new FiltroGrpcWeb(pasarela));
        Tomcat.addServlet(contexto, "resto", new Resto());
        contexto.addServletMappingDecoded("/", "resto");
        tomcat.start();
        url = URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort());
    }

    private static void registrar(Context contexto, String nombre, Filter filtro) {
        FilterDef definicion = new FilterDef();
        definicion.setFilterName(nombre);
        definicion.setFilter(filtro);
        contexto.addFilterDef(definicion);
        FilterMap mapa = new FilterMap();
        mapa.setFilterName(nombre);
        mapa.addURLPattern("/*");
        contexto.addFilterMap(mapa);
    }

    @AfterEach
    void apagar() throws Exception {
        tomcat.stop();
        tomcat.destroy();
        servidor.close();
        try (var archivos = Files.walk(base)) {
            archivos.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void elStubGeneradoLlegaAlServicioAtravesDeTomcat() {
        try (CanalGrpcWeb canal = new CanalGrpcWeb(url, Duration.ofSeconds(2), Duration.ofSeconds(10), 1 << 20)) {
            var respuesta = ServiceStatusServiceGrpc
                    .newBlockingStub(ClientInterceptors.intercept(canal,
                            new InterceptorClienteFirma(new FirmaHmac(SECRETO, 300, Clock.systemUTC()))))
                    .getServiceStatus(GetServiceStatusRequest.getDefaultInstance());
            assertEquals(Transport.TRANSPORT_GRPC_WEB, respuesta.getStatus().getServedVia());
        }
    }

    @Test
    void elChequeoDeSaludPasaDeLargoYLlevaCabecerasDeSeguridad() throws Exception {
        try (HttpClient cliente = HttpClient.newHttpClient()) {
            HttpResponse<String> respuesta = cliente.send(HttpRequest.newBuilder(url.resolve("/actuator/health"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, respuesta.statusCode());
            assertEquals("{\"status\":\"UP\"}", respuesta.body());
            assertEquals("nosniff", respuesta.headers().firstValue("x-content-type-options").orElseThrow());
            assertEquals("DENY", respuesta.headers().firstValue("x-frame-options").orElseThrow());
            assertEquals("no-store", respuesta.headers().firstValue("cache-control").orElseThrow());
        }
    }

    @Test
    void unPostQueNoEsGrpcWebNoEntraEnLaPasarela() throws Exception {
        try (HttpClient cliente = HttpClient.newHttpClient()) {
            HttpResponse<String> respuesta = cliente.send(HttpRequest.newBuilder(
                    url.resolve("/actaflow.system.v1.ServiceStatusService/GetServiceStatus"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, respuesta.statusCode());
        }
    }
}

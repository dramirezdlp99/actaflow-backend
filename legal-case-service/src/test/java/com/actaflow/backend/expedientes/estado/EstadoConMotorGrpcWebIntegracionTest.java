package com.actaflow.backend.expedientes.estado;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.actaflow.backend.expedientes.soporte.ClienteBff;
import com.actaflow.backend.expedientes.soporte.MotorFalso;
import com.actaflow.backend.plataforma.grpc.ClienteGrpc;
import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.backend.plataforma.grpc.Transporte;
import com.actaflow.contracts.system.v1.HealthState;
import com.actaflow.contracts.system.v1.ServiceStatus;
import com.actaflow.contracts.system.v1.Transport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Como en Render Free: el servicio llega al motor por gRPC-Web (HTTP/1.1). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EstadoConMotorGrpcWebIntegracionTest {

    private static final MotorFalso MOTOR = MotorFalso.iniciar();

    @Value("${local.server.port}")
    private int puerto;

    @Autowired
    private ServidorGrpc servidorGrpc;

    @Autowired
    @Qualifier("clienteMotor")
    private ClienteGrpc clienteMotor;

    @DynamicPropertySource
    static void apuntarAlMotorFalso(DynamicPropertyRegistry registro) {
        registro.add("actaflow.motor.transport", () -> "grpc-web");
        registro.add("actaflow.motor.url", () -> MOTOR.url().toString());
    }

    @AfterAll
    static void apagar() {
        MOTOR.close();
    }

    @Test
    void elMotorRespondePorGrpcWebYLoInformaEnSuEstadoAnidado() {
        assertEquals(Transporte.GRPC_WEB, clienteMotor.transporte());
        ServiceStatus estado = ClienteBff.estado("grpc-web", puerto, servidorGrpc.puertoNativo());
        assertEquals(HealthState.HEALTH_STATE_OK, estado.getState());
        assertEquals(Transport.TRANSPORT_GRPC_WEB, estado.getComponents(1).getNested().getServedVia());
    }
}

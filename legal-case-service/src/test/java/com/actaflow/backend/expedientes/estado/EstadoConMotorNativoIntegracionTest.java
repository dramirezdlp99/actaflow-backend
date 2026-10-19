package com.actaflow.backend.expedientes.estado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.actaflow.backend.expedientes.soporte.ClienteBff;
import com.actaflow.backend.expedientes.soporte.MotorFalso;
import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.contracts.system.v1.ComponentKind;
import com.actaflow.contracts.system.v1.ComponentStatus;
import com.actaflow.contracts.system.v1.HealthState;
import com.actaflow.contracts.system.v1.ServiceStatus;
import com.actaflow.contracts.system.v1.Transport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Arranque completo contra PostgreSQL real, con un motor que cumple el contrato y al que el
 * servicio llama por gRPC nativo (HTTP/2), como en local y en CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EstadoConMotorNativoIntegracionTest {

    private static final MotorFalso MOTOR = MotorFalso.iniciar();

    @Value("${local.server.port}")
    private int puerto;

    @Autowired
    private ServidorGrpc servidorGrpc;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void apuntarAlMotorFalso(DynamicPropertyRegistry registro) {
        registro.add("actaflow.motor.transport", () -> "grpc");
        registro.add("actaflow.motor.grpc-target", MOTOR::destinoNativo);
    }

    @AfterAll
    static void apagar() {
        MOTOR.close();
    }

    @Test
    void conBaseYMotorListosElServicioEstaOkYAnidaElEstadoDelMotor() {
        ServiceStatus estado = ClienteBff.estado("grpc-web", puerto, servidorGrpc.puertoNativo());
        assertEquals("legal-case-service", estado.getServiceName());
        assertEquals(HealthState.HEALTH_STATE_OK, estado.getState());
        assertEquals(Transport.TRANSPORT_GRPC_WEB, estado.getServedVia());
        assertEquals(List.of("database", "ai-engine"),
                estado.getComponentsList().stream().map(ComponentStatus::getId).toList());

        ComponentStatus baseDatos = estado.getComponents(0);
        assertEquals(HealthState.HEALTH_STATE_OK, baseDatos.getState());
        assertEquals("3", baseDatos.getVersion());

        ComponentStatus motor = estado.getComponents(1);
        assertEquals(ComponentKind.COMPONENT_KIND_SERVICE, motor.getKind());
        assertEquals(HealthState.HEALTH_STATE_OK, motor.getState());
        assertEquals("0.2.0", motor.getVersion());
        ServiceStatus anidado = motor.getNested();
        assertEquals("ai-engine", anidado.getServiceName());
        assertEquals(Transport.TRANSPORT_GRPC, anidado.getServedVia());
        assertEquals("speech-model", anidado.getComponents(0).getId());
    }

    @Test
    void porGrpcNativoElBffObtieneLoMismo() {
        ServiceStatus estado = ClienteBff.estado("grpc", puerto, servidorGrpc.puertoNativo());
        assertEquals(Transport.TRANSPORT_GRPC, estado.getServedVia());
        assertEquals(HealthState.HEALTH_STATE_OK, estado.getState());
    }

    @Test
    void elServicioFirmaSusLlamadasAlMotorConElSecretoDelPar() {
        int antes = MOTOR.comprobaciones();
        ClienteBff.estado("grpc", puerto, servidorGrpc.puertoNativo());
        // El motor falso exige su propio secreto: si la llamada llego, iba bien firmada.
        assertTrue(MOTOR.comprobaciones() > antes);
    }

    @Test
    void flywayMigroElEsquemaPropioConExpedientesYRetiroLasTablasDelEsquemaPublico() {
        assertEquals(List.of("1", "2", "3"), jdbc.queryForList(
                "select version from legal_case.flyway_schema_history where success and version is not null"
                        + " order by installed_rank",
                String.class));
        assertEquals(1, contarTablas("legal_case", "trabajo"));
        for (String tabla : List.of("caso", "participante", "evento_caso", "idempotencia")) {
            assertEquals(1, contarTablas("legal_case", tabla), tabla);
        }
        assertEquals(0, contarTablas("public", "trabajo"));
        assertEquals(0, contarTablas("public", "flyway_schema_history"));
    }

    private int contarTablas(String esquema, String tabla) {
        Integer total = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = ? and table_name = ?",
                Integer.class, esquema, tabla);
        return total == null ? 0 : total;
    }
}

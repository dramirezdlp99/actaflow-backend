package com.actaflow.backend.estado.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpResponse;
import java.time.Clock;

import com.actaflow.backend.compartido.seguridad.FirmaHmac;
import com.actaflow.backend.soporte.ClienteBff;
import com.actaflow.backend.soporte.DatosPrueba;
import com.actaflow.backend.soporte.MotorFalso;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.json.JsonMapper;

/** Con un motor que respeta el contrato real de /health, el sistema completo queda OK. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EstadoConMotorIntegracionTest {

    private static final MotorFalso MOTOR = MotorFalso.iniciar();

    @Value("${local.server.port}")
    private int puerto;

    @DynamicPropertySource
    static void apuntarAlMotorFalso(DynamicPropertyRegistry registro) {
        registro.add("actaflow.motor.url", () -> MOTOR.url().toString());
    }

    @AfterAll
    static void apagar() {
        MOTOR.close();
    }

    @Test
    void conMotorListoElSistemaEstaOk() {
        HttpResponse<String> respuesta = new ClienteBff(puerto).getFirmado("/api/v1/status");

        assertEquals(200, respuesta.statusCode());
        RespuestaEstado estado = JsonMapper.builder().build().readValue(respuesta.body(), RespuestaEstado.class);
        assertEquals("OK", estado.estado());
        assertEquals("OK", estado.componentes().motorIa().estado());
        assertEquals("0.1.0", estado.componentes().motorIa().version());
        assertEquals("Modelo de voz tiny listo", estado.componentes().motorIa().detalle());
    }

    @Test
    void elBackendFirmaSuPeticionAlMotorConElSecretoDelMotor() {
        new ClienteBff(puerto).getFirmado("/api/v1/status");

        FirmaHmac firmaMotor = new FirmaHmac(DatosPrueba.SECRETO_MOTOR, 300, Clock.systemUTC());
        String ts = MOTOR.cabecera(FirmaHmac.CABECERA_TIMESTAMP);
        String valor = MOTOR.cabecera(FirmaHmac.CABECERA_FIRMA);
        assertDoesNotThrow(() -> firmaMotor.verificar(ts, valor, "GET", "/health", new byte[0]));
        assertEquals("HTTP/1.1", MOTOR.ultimoProtocolo());
    }
}

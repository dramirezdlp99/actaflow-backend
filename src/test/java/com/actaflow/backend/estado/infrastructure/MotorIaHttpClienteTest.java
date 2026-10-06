package com.actaflow.backend.estado.infrastructure;

import static com.actaflow.backend.soporte.DatosPrueba.SECRETO_MOTOR;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

import com.actaflow.backend.compartido.seguridad.FirmaHmac;
import com.actaflow.backend.estado.domain.EstadoComponente;
import com.actaflow.backend.estado.domain.VerificacionComponente;
import com.actaflow.backend.soporte.MotorFalso;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MotorIaHttpClienteTest {

    private final MotorFalso motor = MotorFalso.iniciar();
    private final FirmaHmac firma = new FirmaHmac(SECRETO_MOTOR, 300, Clock.systemUTC());

    @AfterEach
    void apagar() {
        motor.close();
    }

    private MotorIaHttpCliente cliente(URI url, Duration timeoutPeticion) {
        return new MotorIaHttpCliente(url, firma, Duration.ofSeconds(1), timeoutPeticion);
    }

    @Test
    void motorListoQuedaOkConSuVersion() {
        VerificacionComponente resultado = cliente(motor.url(), Duration.ofSeconds(2)).verificar();

        assertEquals(EstadoComponente.OK, resultado.estado());
        assertEquals("0.1.0", resultado.version());
        assertEquals("Modelo de voz tiny listo", resultado.detalle());
    }

    @Test
    void laPeticionVaFirmadaPorHttp11YSinIntentoDeSubirAHttp2() {
        cliente(motor.url(), Duration.ofSeconds(2)).verificar();

        assertEquals("/health", motor.ultimaRuta());
        assertEquals("HTTP/1.1", motor.ultimoProtocolo());
        assertNull(motor.cabecera("Upgrade"), "no debe pedir Upgrade: h2c");
        String ts = motor.cabecera(FirmaHmac.CABECERA_TIMESTAMP);
        String valor = motor.cabecera(FirmaHmac.CABECERA_FIRMA);
        assertDoesNotThrow(() -> firma.verificar(ts, valor, "GET", "/health", new byte[0]));
    }

    @Test
    void motorSinElModeloConfiguradoQuedaDegradado() {
        motor.responder(200, MotorFalso.HEALTH_SIN_MODELO);

        VerificacionComponente resultado = cliente(motor.url(), Duration.ofSeconds(2)).verificar();

        assertEquals(EstadoComponente.DEGRADADO, resultado.estado());
        assertEquals("Modelo de voz base no instalado", resultado.detalle());
    }

    @Test
    void errorHttpQuedaCaido() {
        motor.responder(503, "{}");

        VerificacionComponente resultado = cliente(motor.url(), Duration.ofSeconds(2)).verificar();

        assertEquals(EstadoComponente.CAIDO, resultado.estado());
        assertEquals("El motor respondio HTTP 503", resultado.detalle());
    }

    @Test
    void jsonIlegibleQuedaCaido() {
        motor.responder(200, "esto no es json");

        VerificacionComponente resultado = cliente(motor.url(), Duration.ofSeconds(2)).verificar();

        assertEquals(EstadoComponente.CAIDO, resultado.estado());
        assertEquals("Respuesta ilegible del motor", resultado.detalle());
    }

    @Test
    void motorLentoSeCortaPorTiempoYSeReportaComoDespertando() {
        motor.tardar(Duration.ofSeconds(3));

        VerificacionComponente resultado = cliente(motor.url(), Duration.ofMillis(300)).verificar();

        assertEquals(EstadoComponente.CAIDO, resultado.estado());
        assertTrue(resultado.detalle().contains("despertando"), resultado.detalle());
    }

    @Test
    void motorInalcanzableQuedaCaido() {
        VerificacionComponente resultado = cliente(URI.create("http://127.0.0.1:9"), Duration.ofSeconds(1))
                .verificar();

        assertEquals(EstadoComponente.CAIDO, resultado.estado());
    }

    @Test
    void respuestaSinModelosQuedaDegradada() {
        VerificacionComponente resultado = MotorIaHttpCliente.interpretar(
                new SaludMotor("actaflow-ai-engine", "0.1.0", "ok", null), 4);

        assertEquals(EstadoComponente.DEGRADADO, resultado.estado());
    }
}

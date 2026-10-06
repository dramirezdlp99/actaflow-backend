package com.actaflow.backend.estado.application;

import static com.actaflow.backend.soporte.DatosPrueba.relojFijo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import com.actaflow.backend.estado.domain.EstadoComponente;
import com.actaflow.backend.estado.domain.EstadoSistema;
import com.actaflow.backend.estado.domain.VerificacionComponente;
import com.actaflow.backend.soporte.VerificadorFalso;

import org.junit.jupiter.api.Test;

class ConsultarEstadoServiceTest {

    private static final VerificacionComponente BD_OK = VerificacionComponente.ok(3, "esquema v1", "1");
    private static final VerificacionComponente MOTOR_OK = VerificacionComponente.ok(8, "tiny listo", "0.1.0");

    private static ConsultarEstadoService servicio(VerificadorFalso bd, VerificadorFalso motor,
                                                   Duration limiteBd, Duration limiteMotor) {
        return new ConsultarEstadoService(bd, motor, limiteBd, limiteMotor, "0.1.0", relojFijo(1_760_000_000L));
    }

    @Test
    void todoOkCuandoAmbasCapasResponden() {
        EstadoSistema estado = servicio(VerificadorFalso.queResponde(BD_OK), VerificadorFalso.queResponde(MOTOR_OK),
                Duration.ofSeconds(2), Duration.ofSeconds(2)).consultar();

        assertEquals(EstadoSistema.EstadoGeneral.OK, estado.estado());
        assertEquals("0.1.0", estado.version());
        assertEquals(Instant.ofEpochSecond(1_760_000_000L), estado.verificadoEn());
        assertEquals(BD_OK, estado.baseDatos());
        assertEquals(MOTOR_OK, estado.motorIa());
    }

    @Test
    void unMotorDormidoNoBloqueaLaRespuestaYQuedaCaido() {
        VerificadorFalso motorLento = VerificadorFalso.queTarda(Duration.ofSeconds(10), MOTOR_OK);
        long inicio = System.nanoTime();

        EstadoSistema estado = servicio(VerificadorFalso.queResponde(BD_OK), motorLento,
                Duration.ofSeconds(2), Duration.ofMillis(300)).consultar();

        long ms = Duration.ofNanos(System.nanoTime() - inicio).toMillis();
        assertTrue(ms < 2_000, "tardo " + ms + " ms");
        assertEquals(EstadoComponente.CAIDO, estado.motorIa().estado());
        assertTrue(estado.motorIa().detalle().contains("despertando"));
        assertEquals(EstadoComponente.OK, estado.baseDatos().estado());
        assertEquals(EstadoSistema.EstadoGeneral.DEGRADADO, estado.estado());
    }

    @Test
    void lasVerificacionesCorrenEnParalelo() {
        VerificadorFalso bd = VerificadorFalso.queTarda(Duration.ofMillis(400), BD_OK);
        VerificadorFalso motor = VerificadorFalso.queTarda(Duration.ofMillis(400), MOTOR_OK);
        long inicio = System.nanoTime();

        EstadoSistema estado = servicio(bd, motor, Duration.ofSeconds(3), Duration.ofSeconds(3)).consultar();

        long ms = Duration.ofNanos(System.nanoTime() - inicio).toMillis();
        assertEquals(EstadoSistema.EstadoGeneral.OK, estado.estado());
        assertTrue(ms < 750, "en serie habria tardado unos 800 ms; tardo " + ms);
    }

    @Test
    void unaExcepcionEnElVerificadorSeReportaComoCaidoSinFiltrarElMensaje() {
        VerificadorFalso bdRota = VerificadorFalso.queFalla(new IllegalStateException("password=secreta"));

        EstadoSistema estado = servicio(bdRota, VerificadorFalso.queResponde(MOTOR_OK),
                Duration.ofSeconds(2), Duration.ofSeconds(2)).consultar();

        assertEquals(EstadoComponente.CAIDO, estado.baseDatos().estado());
        assertEquals("Error al verificar", estado.baseDatos().detalle());
        assertEquals(EstadoSistema.EstadoGeneral.DEGRADADO, estado.estado());
    }

    @Test
    void cadaConsultaVerificaDeNuevoAmbasCapas() {
        VerificadorFalso bd = VerificadorFalso.queResponde(BD_OK);
        VerificadorFalso motor = VerificadorFalso.queResponde(MOTOR_OK);
        ConsultarEstadoService servicio = servicio(bd, motor, Duration.ofSeconds(2), Duration.ofSeconds(2));

        servicio.consultar();
        servicio.consultar();

        assertEquals(2, bd.llamadas());
        assertEquals(2, motor.llamadas());
    }
}

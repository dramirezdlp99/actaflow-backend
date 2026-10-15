package com.actaflow.backend.plataforma.seguridad;

import static com.actaflow.backend.plataforma.soporte.ServiciosFalsos.SECRETO;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import com.actaflow.backend.plataforma.grpc.Metadatos;
import com.actaflow.contracts.engine.v1.ModelSize;
import com.actaflow.contracts.engine.v1.RunBenchmarkRequest;

import io.grpc.Metadata;

import org.junit.jupiter.api.Test;

class FirmaHmacTest {

    private static final long AHORA = 1_760_000_000L;
    private static final String TS = Long.toString(AHORA);
    private static final String ESTADO = "actaflow.system.v1.ServiceStatusService/GetServiceStatus";
    private static final String BENCHMARK = "actaflow.engine.v1.EngineDiagnosticsService/RunBenchmark";
    private static final String FLUJO = "actaflow.engine.v1.TranscriptionService/StreamTranscription";
    private static final byte[] TINY = HexFormat.of().parseHex("0a0101");

    private static FirmaHmac firma(long ahora) {
        return new FirmaHmac(SECRETO, 300, Clock.fixed(Instant.ofEpochSecond(ahora), ZoneOffset.UTC));
    }

    @Test
    void elMensajeDeBenchmarkSeSerializaIgualQueEnLosOtrosLenguajes() {
        RunBenchmarkRequest peticion = RunBenchmarkRequest.newBuilder().addModels(ModelSize.MODEL_SIZE_TINY).build();
        assertEquals("0a0101", HexFormat.of().formatHex(peticion.toByteArray()));
    }

    @Test
    void coincideConLosVectoresCompartidosConElBffYElMotor() {
        FirmaHmac f = firma(AHORA);
        assertEquals("2464942d647557cdabd41b2f6cdc7dcb23a204184913160c1e5df4d7c284778d",
                f.firmar(TS, ESTADO, FirmaHmac.SIN_CUERPO));
        assertEquals("a58c8e638550007e92cb44c0e022ebe86c6fd260c7a310750a3080af78866cd3",
                f.firmar(TS, BENCHMARK, TINY));
        assertEquals("4c49b46fc8ed57ee5358b19be415110fbc39e3c74493f9ccf522de2401bf86e0",
                f.firmar(TS, FLUJO, FirmaHmac.SIN_CUERPO));
    }

    @Test
    void laRutaLlevaBarraInicialConOSinElla() {
        assertEquals(FirmaHmac.mensajeCanonico(TS, "/" + ESTADO, FirmaHmac.SIN_CUERPO),
                FirmaHmac.mensajeCanonico(TS, ESTADO, FirmaHmac.SIN_CUERPO));
        assertEquals(TS + "\nRPC\n/" + ESTADO
                + "\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                FirmaHmac.mensajeCanonico(TS, ESTADO, FirmaHmac.SIN_CUERPO));
    }

    @Test
    void aceptaUnaFirmaValidaAunqueVengaEnMayusculasYEnElBordeDeLaVentana() {
        String valida = firma(AHORA).firmar(TS, BENCHMARK, TINY).toUpperCase();
        assertDoesNotThrow(() -> firma(AHORA + 300).verificar(TS, valida, BENCHMARK, TINY));
    }

    @Test
    void rechazaPorCadaMotivo() {
        FirmaHmac f = firma(AHORA);
        String valida = f.firmar(TS, BENCHMARK, TINY);
        assertMotivo(FirmaInvalidaException.Motivo.FALTA_METADATO, () -> f.verificar(null, valida, BENCHMARK, TINY));
        assertMotivo(FirmaInvalidaException.Motivo.FALTA_METADATO, () -> f.verificar(TS, " ", BENCHMARK, TINY));
        assertMotivo(FirmaInvalidaException.Motivo.TIMESTAMP_INVALIDO,
                () -> f.verificar("1.5", valida, BENCHMARK, TINY));
        assertMotivo(FirmaInvalidaException.Motivo.TIMESTAMP_INVALIDO,
                () -> f.verificar("9999999999999", valida, BENCHMARK, TINY));
        assertMotivo(FirmaInvalidaException.Motivo.FUERA_DE_VENTANA,
                () -> firma(AHORA + 301).verificar(TS, valida, BENCHMARK, TINY));
        assertMotivo(FirmaInvalidaException.Motivo.FIRMA_INCORRECTA, () -> f.verificar(TS, valida, ESTADO, TINY));
        assertMotivo(FirmaInvalidaException.Motivo.FIRMA_INCORRECTA,
                () -> f.verificar(TS, valida, BENCHMARK, HexFormat.of().parseHex("0a0102")));
    }

    @Test
    void losMetadatosGeneradosSonVerificables() {
        FirmaHmac f = firma(AHORA);
        Metadata metadatos = f.metadatos(ESTADO, FirmaHmac.SIN_CUERPO);
        assertEquals(TS, metadatos.get(Metadatos.TIMESTAMP));
        assertDoesNotThrow(() -> f.verificar(metadatos.get(Metadatos.TIMESTAMP), metadatos.get(Metadatos.FIRMA),
                ESTADO, FirmaHmac.SIN_CUERPO));
    }

    @Test
    void rechazaSecretosCortos() {
        assertThrows(IllegalArgumentException.class, () -> new FirmaHmac("corto", 300, Clock.systemUTC()));
    }

    private static void assertMotivo(FirmaInvalidaException.Motivo esperado, Runnable accion) {
        FirmaInvalidaException e = assertThrows(FirmaInvalidaException.class, accion::run);
        assertEquals(esperado, e.motivo());
    }
}

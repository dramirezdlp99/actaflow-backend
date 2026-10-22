package com.actaflow.backend.expedientes.casos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.actaflow.backend.expedientes.grpc.ServicioClavesProcesadorGrpc;
import com.actaflow.contracts.crypto.v1.GetProcessorKeysRequest;
import com.actaflow.contracts.crypto.v1.GetProcessorKeysResponse;
import com.actaflow.contracts.crypto.v1.ProcessorKey;
import com.actaflow.contracts.crypto.v1.ProcessorKind;
import com.actaflow.contracts.crypto.v1.PublicKey;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import org.junit.jupiter.api.Test;

/** La clave del motor se pide una vez, se guarda 10 minutos y un motor caido responde UNAVAILABLE. */
class ClavesProcesadorTest {

    private Instant ahora = Instant.parse("2026-10-22T15:00:00Z");
    private final Clock reloj = new Clock() {
        @Override
        public Instant instant() {
            return ahora;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return this;
        }
    };

    private int llamadas;
    private boolean caido;

    private GetProcessorKeysResponse motor() {
        llamadas++;
        if (caido) {
            throw Status.UNAVAILABLE.withDescription("dormido").asRuntimeException();
        }
        return GetProcessorKeysResponse.newBuilder().addKeys(ProcessorKey.newBuilder()
                .setKind(ProcessorKind.PROCESSOR_KIND_AI_ENGINE)
                .setKey(PublicKey.newBuilder().setKeyId("motor:" + llamadas))).build();
    }

    private static final class Respuesta implements StreamObserver<GetProcessorKeysResponse> {
        final List<GetProcessorKeysResponse> valores = new ArrayList<>();
        Throwable error;

        @Override
        public void onNext(GetProcessorKeysResponse valor) {
            valores.add(valor);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }

        @Override
        public void onCompleted() {
        }
    }

    private Respuesta pedir(ServicioClavesProcesadorGrpc servicio) {
        Respuesta r = new Respuesta();
        servicio.getProcessorKeys(GetProcessorKeysRequest.getDefaultInstance(), r);
        return r;
    }

    private static String id(Respuesta r) {
        return r.valores.get(0).getKeys(0).getKey().getKeyId();
    }

    @Test
    void guardaLaClaveYLaRenuevaAlVencer() {
        ServicioClavesProcesadorGrpc servicio = new ServicioClavesProcesadorGrpc(this::motor, reloj,
                Duration.ofMinutes(10));
        assertEquals("motor:1", id(pedir(servicio)));
        ahora = ahora.plus(Duration.ofMinutes(9));
        assertEquals("motor:1", id(pedir(servicio)));
        assertEquals(1, llamadas, "dentro de la vigencia no se vuelve a llamar al motor");
        ahora = ahora.plus(Duration.ofMinutes(2));
        assertEquals("motor:2", id(pedir(servicio)));
    }

    @Test
    void unMotorCaidoRespondeUnavailableYNoQuedaGuardado() {
        ServicioClavesProcesadorGrpc servicio = new ServicioClavesProcesadorGrpc(this::motor, reloj,
                Duration.ofMinutes(10));
        caido = true;
        Respuesta r = pedir(servicio);
        assertEquals(Status.Code.UNAVAILABLE, Status.fromThrowable(r.error).getCode());
        assertEquals(0, r.valores.size());
        caido = false;
        Respuesta bien = pedir(servicio);
        assertNull(bien.error);
        assertEquals("motor:2", id(bien));
    }
}

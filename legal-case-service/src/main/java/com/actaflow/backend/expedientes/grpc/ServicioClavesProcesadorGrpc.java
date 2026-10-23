package com.actaflow.backend.expedientes.grpc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.actaflow.contracts.crypto.v1.GetProcessorKeysRequest;
import com.actaflow.contracts.crypto.v1.GetProcessorKeysResponse;
import com.actaflow.contracts.crypto.v1.ProcessorKeyServiceGrpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

/**
 * ProcessorKeyService: la clave publica del motor de IA, para que el navegador le selle el audio y
 * las transcripciones. El BFF no habla con el motor; legal-case si, y se la pide y la guarda un
 * rato (la clave cambia solo si se rota la variable de entorno del motor). La clave es publica: lo
 * que se protege es que el navegador la reciba del backend autenticado y no de cualquier sitio.
 */
public final class ServicioClavesProcesadorGrpc extends ProcessorKeyServiceGrpc.ProcessorKeyServiceImplBase {

    private static final System.Logger LOG = System.getLogger(ServicioClavesProcesadorGrpc.class.getName());

    private final Supplier<GetProcessorKeysResponse> motor;
    private final Clock reloj;
    private final Duration vigencia;
    private final ReentrantLock candado = new ReentrantLock();
    private GetProcessorKeysResponse guardada;
    private Instant vence = Instant.MIN;

    public ServicioClavesProcesadorGrpc(Supplier<GetProcessorKeysResponse> motor, Clock reloj, Duration vigencia) {
        this.motor = motor;
        this.reloj = reloj;
        this.vigencia = vigencia;
    }

    @Override
    public void getProcessorKeys(GetProcessorKeysRequest p, StreamObserver<GetProcessorKeysResponse> r) {
        GetProcessorKeysResponse claves;
        try {
            claves = claves();
        } catch (StatusRuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "El motor de IA no entrego su clave: {0}", e.getStatus());
            r.onError(Status.UNAVAILABLE.withDescription("El motor de IA no responde; intenta de nuevo en un momento")
                    .asRuntimeException());
            return;
        }
        r.onNext(claves);
        r.onCompleted();
    }

    /**
     * ReentrantLock y no synchronized: cada llamada gRPC corre en un hilo virtual, y en Java 21 un hilo
     * virtual que espera dentro de synchronized bloquea su hilo real. En Render Free hay uno solo, asi
     * que esperar al motor dormido (hasta 45 s) congelaba todo el servicio, health check incluido.
     */
    private GetProcessorKeysResponse claves() {
        candado.lock();
        try {
            Instant ahora = reloj.instant();
            if (guardada == null || !ahora.isBefore(vence)) {
                GetProcessorKeysResponse nuevas = motor.get();
                if (nuevas.getKeysCount() == 0) {
                    throw Status.UNAVAILABLE.withDescription("El motor no publico claves").asRuntimeException();
                }
                guardada = nuevas;
                vence = ahora.plus(vigencia);
            }
            return guardada;
        } finally {
            candado.unlock();
        }
    }
}

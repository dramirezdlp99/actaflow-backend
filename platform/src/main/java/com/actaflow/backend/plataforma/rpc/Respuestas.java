package com.actaflow.backend.plataforma.rpc;

import java.lang.System.Logger.Level;
import java.util.function.Supplier;

import com.actaflow.contracts.common.v1.ErrorReason;
import com.actaflow.contracts.common.v1.FieldViolation;
import com.actaflow.contracts.common.v1.FieldViolations;
import com.google.protobuf.Any;
import com.google.rpc.Code;

import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;

/**
 * Ejecuta un caso de uso y traduce sus fallos a codigos gRPC. Las violaciones de campo viajan como
 * detalle tipado {@code actaflow.common.v1.FieldViolations} en {@code grpc-status-details-bin}, junto a
 * la razon ({@code ErrorReason}) y el detalle propio del fallo cuando los hay.
 */
public final class Respuestas {

    private static final System.Logger LOG = System.getLogger(Respuestas.class.getName());

    private Respuestas() {
    }

    public static <T> void responder(StreamObserver<T> observador, Supplier<T> caso) {
        T resultado;
        try {
            resultado = caso.get();
        } catch (FalloRpc fallo) {
            observador.onError(error(fallo));
            return;
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Fallo inesperado en un caso de uso", e);
            observador.onError(Status.INTERNAL.withDescription("Error interno").asRuntimeException());
            return;
        }
        observador.onNext(resultado);
        observador.onCompleted();
    }

    public static RuntimeException error(FalloRpc fallo) {
        Code codigo = switch (fallo.tipo()) {
            case ARGUMENTO_INVALIDO -> Code.INVALID_ARGUMENT;
            case NO_AUTENTICADO -> Code.UNAUTHENTICATED;
            case SIN_PERMISO -> Code.PERMISSION_DENIED;
            case NO_ENCONTRADO -> Code.NOT_FOUND;
            case YA_EXISTE -> Code.ALREADY_EXISTS;
            case PRECONDICION -> Code.FAILED_PRECONDITION;
            case ABORTADO -> Code.ABORTED;
        };
        com.google.rpc.Status.Builder estado = com.google.rpc.Status.newBuilder()
                .setCode(codigo.getNumber()).setMessage(fallo.getMessage());
        if (!fallo.violaciones().isEmpty()) {
            FieldViolations.Builder detalles = FieldViolations.newBuilder();
            fallo.violaciones().forEach(v -> detalles.addViolations(FieldViolation.newBuilder()
                    .setField(v.campo()).setRule(v.regla()).setDescription(v.descripcion())));
            estado.addDetails(Any.pack(detalles.build()));
        }
        if (fallo.razon() != null) {
            estado.addDetails(Any.pack(ErrorReason.newBuilder().setReason(fallo.razon()).build()));
        }
        if (fallo.detalle() != null) {
            estado.addDetails(Any.pack(fallo.detalle()));
        }
        return StatusProto.toStatusRuntimeException(estado.build());
    }
}

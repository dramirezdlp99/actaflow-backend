package com.actaflow.backend.identidad.grpc;

import java.lang.System.Logger.Level;
import java.util.function.Supplier;

import com.actaflow.backend.identidad.dominio.FalloIdentidad;
import com.actaflow.contracts.common.v1.FieldViolation;
import com.actaflow.contracts.common.v1.FieldViolations;
import com.google.protobuf.Any;
import com.google.rpc.Code;

import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;

/** Ejecuta un caso de uso y traduce sus fallos a codigos gRPC con detalles tipados. */
final class Respuestas {

    private static final System.Logger LOG = System.getLogger(Respuestas.class.getName());

    private Respuestas() {
    }

    static <T> void responder(StreamObserver<T> observador, Supplier<T> caso) {
        T resultado;
        try {
            resultado = caso.get();
        } catch (FalloIdentidad fallo) {
            observador.onError(error(fallo));
            return;
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Fallo inesperado en el servicio de identidad", e);
            observador.onError(Status.INTERNAL.withDescription("Error interno").asRuntimeException());
            return;
        }
        observador.onNext(resultado);
        observador.onCompleted();
    }

    static RuntimeException error(FalloIdentidad fallo) {
        Code codigo = switch (fallo.tipo()) {
            case ARGUMENTO_INVALIDO -> Code.INVALID_ARGUMENT;
            case NO_AUTENTICADO -> Code.UNAUTHENTICATED;
            case SIN_PERMISO -> Code.PERMISSION_DENIED;
            case NO_ENCONTRADO -> Code.NOT_FOUND;
            case YA_EXISTE -> Code.ALREADY_EXISTS;
            case PRECONDICION -> Code.FAILED_PRECONDITION;
        };
        com.google.rpc.Status.Builder estado = com.google.rpc.Status.newBuilder()
                .setCode(codigo.getNumber()).setMessage(fallo.getMessage());
        if (!fallo.violaciones().isEmpty()) {
            FieldViolations.Builder detalles = FieldViolations.newBuilder();
            fallo.violaciones().forEach(v -> detalles.addViolations(FieldViolation.newBuilder()
                    .setField(v.campo()).setRule(v.regla()).setDescription(v.descripcion())));
            estado.addDetails(Any.pack(detalles.build()));
        }
        return StatusProto.toStatusRuntimeException(estado.build());
    }
}

package com.actaflow.backend.expedientes.grpc;

import static com.actaflow.backend.plataforma.rpc.Respuestas.responder;

import java.util.UUID;

import com.actaflow.backend.expedientes.aplicacion.ServicioColaboracion;
import com.actaflow.backend.expedientes.seguridad.InterceptorSesion;
import com.actaflow.contracts.collaboration.v1.CollaborationServiceGrpc;
import com.actaflow.contracts.collaboration.v1.CompactDocumentRequest;
import com.actaflow.contracts.collaboration.v1.CompactDocumentResponse;
import com.actaflow.contracts.collaboration.v1.EncryptedUpdate;
import com.actaflow.contracts.collaboration.v1.GetDocumentStateRequest;
import com.actaflow.contracts.collaboration.v1.GetDocumentStateResponse;
import com.actaflow.contracts.collaboration.v1.PushUpdatesRequest;
import com.actaflow.contracts.collaboration.v1.PushUpdatesResponse;

import io.grpc.stub.StreamObserver;

/**
 * CollaborationService del contrato, todo con sesion. IssueCollaborationTicket queda sin implementar
 * (UNIMPLEMENTED): en Render Free los servicios hablan gRPC-Web por HTTP/1.1, asi que el frontend
 * sigue el borrador consultando GetDocumentState desde un Shared Worker (una sola consulta por
 * navegador, compartida por todas sus pestanas) en lugar de abrir un WebSocket.
 */
public final class ServicioColaboracionGrpc extends CollaborationServiceGrpc.CollaborationServiceImplBase {

    private final ServicioColaboracion servicio;

    public ServicioColaboracionGrpc(ServicioColaboracion servicio) {
        this.servicio = servicio;
    }

    @Override
    public void getDocumentState(GetDocumentStateRequest p, StreamObserver<GetDocumentStateResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("minute_id", p.getMinuteId())
                    .cumple("after_sequence", "int64.gte", p.getAfterSequence() >= 0,
                            "value must be greater than or equal to 0")
                    .verificar();
            return servicio.estado(InterceptorSesion.actual(), UUID.fromString(p.getMinuteId()),
                    p.getAfterSequence());
        });
    }

    @Override
    public void pushUpdates(PushUpdatesRequest p, StreamObserver<PushUpdatesResponse> r) {
        responder(r, () -> {
            Reglas reglas = new Reglas().uuid("minute_id", p.getMinuteId())
                    .cumple("updates", "repeated.min_items", p.getUpdatesCount() >= 1,
                            "value must contain at least 1 item(s)")
                    .cumple("updates", "repeated.max_items", p.getUpdatesCount() <= 200,
                            "value must contain no more than 200 item(s)");
            for (int i = 0; i < p.getUpdatesCount(); i += 1) {
                EncryptedUpdate u = p.getUpdates(i);
                reglas.uuid("updates[" + i + "].update_id", u.getUpdateId())
                        .blob("updates[" + i + "].update", u.hasUpdate(), u.getUpdate());
            }
            reglas.verificar();
            return servicio.enviar(InterceptorSesion.actual(), p);
        });
    }

    @Override
    public void compactDocument(CompactDocumentRequest p, StreamObserver<CompactDocumentResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("minute_id", p.getMinuteId())
                    .blob("snapshot", p.hasSnapshot(), p.getSnapshot())
                    .cumple("covers_sequence", "int64.gte", p.getCoversSequence() >= 1,
                            "value must be greater than or equal to 1")
                    .verificar();
            return servicio.compactar(InterceptorSesion.actual(), p);
        });
    }
}

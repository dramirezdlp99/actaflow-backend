package com.actaflow.backend.expedientes.grpc;

import static com.actaflow.backend.plataforma.rpc.Respuestas.responder;

import java.util.UUID;

import com.actaflow.backend.expedientes.aplicacion.ServicioCasos;
import com.actaflow.backend.expedientes.seguridad.InterceptorSesion;
import com.actaflow.contracts.cases.v1.CaseKind;
import com.actaflow.contracts.cases.v1.CaseServiceGrpc;
import com.actaflow.contracts.cases.v1.CaseStatus;
import com.actaflow.contracts.cases.v1.ChangeCaseStatusRequest;
import com.actaflow.contracts.cases.v1.ChangeCaseStatusResponse;
import com.actaflow.contracts.cases.v1.CreateCaseRequest;
import com.actaflow.contracts.cases.v1.CreateCaseResponse;
import com.actaflow.contracts.cases.v1.GetCaseRequest;
import com.actaflow.contracts.cases.v1.GetCaseResponse;
import com.actaflow.contracts.cases.v1.ListCaseEventsRequest;
import com.actaflow.contracts.cases.v1.ListCaseEventsResponse;
import com.actaflow.contracts.cases.v1.ListCasesRequest;
import com.actaflow.contracts.cases.v1.ListCasesResponse;
import com.actaflow.contracts.cases.v1.UpdateCaseRequest;
import com.actaflow.contracts.cases.v1.UpdateCaseResponse;

import io.grpc.stub.StreamObserver;

/** CaseService del contrato: valida la peticion y delega en el caso de uso. */
public final class ServicioCasosGrpc extends CaseServiceGrpc.CaseServiceImplBase {

    private final ServicioCasos servicio;

    public ServicioCasosGrpc(ServicioCasos servicio) {
        this.servicio = servicio;
    }

    @Override
    public void createCase(CreateCaseRequest p, StreamObserver<CreateCaseResponse> r) {
        responder(r, () -> {
            new Reglas().patron("case_number", p.getCaseNumber(), Reglas.RADICADO)
                    .elegido("kind", p.getKind(), p.getKind() != CaseKind.UNRECOGNIZED)
                    .blob("details", p.hasDetails(), p.getDetails())
                    .claveEnvuelta("wrapped_case_key", p.hasWrappedCaseKey(), p.getWrappedCaseKey())
                    .uuid("idempotency_key", p.getIdempotencyKey())
                    .verificar();
            return servicio.crear(InterceptorSesion.actual(), p);
        });
    }

    @Override
    public void getCase(GetCaseRequest p, StreamObserver<GetCaseResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("case_id", p.getCaseId()).verificar();
            return servicio.obtener(InterceptorSesion.actual(), UUID.fromString(p.getCaseId()));
        });
    }

    @Override
    public void listCases(ListCasesRequest p, StreamObserver<ListCasesResponse> r) {
        responder(r, () -> {
            new Reglas().pagina("page", p.hasPage(), p.getPage())
                    .definido("status", p.getStatus() != CaseStatus.UNRECOGNIZED)
                    .verificar();
            return servicio.listar(InterceptorSesion.actual(), p.getPage().getPageSize(), p.getPage().getPageToken(),
                    p.getStatus());
        });
    }

    @Override
    public void updateCase(UpdateCaseRequest p, StreamObserver<UpdateCaseResponse> r) {
        responder(r, () -> {
            Reglas reglas = new Reglas().uuid("case_id", p.getCaseId())
                    .cumple("expected_revision", "int64.gte", p.getExpectedRevision() >= 1,
                            "value must be greater than or equal to 1")
                    .uuid("idempotency_key", p.getIdempotencyKey());
            if (p.hasDetails()) {
                reglas.blob("details", true, p.getDetails());
            }
            reglas.verificar();
            return servicio.actualizar(InterceptorSesion.actual(), p);
        });
    }

    @Override
    public void changeCaseStatus(ChangeCaseStatusRequest p, StreamObserver<ChangeCaseStatusResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("case_id", p.getCaseId())
                    .elegido("status", p.getStatus(), p.getStatus() != CaseStatus.UNRECOGNIZED)
                    .uuid("idempotency_key", p.getIdempotencyKey())
                    .verificar();
            return servicio.cambiarEstado(InterceptorSesion.actual(), p);
        });
    }

    @Override
    public void listCaseEvents(ListCaseEventsRequest p, StreamObserver<ListCaseEventsResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("case_id", p.getCaseId()).pagina("page", p.hasPage(), p.getPage()).verificar();
            return servicio.eventos(InterceptorSesion.actual(), UUID.fromString(p.getCaseId()),
                    p.getPage().getPageSize(), p.getPage().getPageToken());
        });
    }
}

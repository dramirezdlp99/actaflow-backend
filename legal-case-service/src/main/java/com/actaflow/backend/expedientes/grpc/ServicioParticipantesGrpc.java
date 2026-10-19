package com.actaflow.backend.expedientes.grpc;

import static com.actaflow.backend.plataforma.rpc.Respuestas.responder;

import java.util.UUID;

import com.actaflow.backend.expedientes.aplicacion.ServicioParticipantes;
import com.actaflow.backend.expedientes.seguridad.InterceptorSesion;
import com.actaflow.contracts.cases.v1.CaseRole;
import com.actaflow.contracts.cases.v1.ChangeParticipantRoleRequest;
import com.actaflow.contracts.cases.v1.ChangeParticipantRoleResponse;
import com.actaflow.contracts.cases.v1.GrantCaseKeyRequest;
import com.actaflow.contracts.cases.v1.GrantCaseKeyResponse;
import com.actaflow.contracts.cases.v1.InviteParticipantRequest;
import com.actaflow.contracts.cases.v1.InviteParticipantResponse;
import com.actaflow.contracts.cases.v1.ListParticipantsRequest;
import com.actaflow.contracts.cases.v1.ListParticipantsResponse;
import com.actaflow.contracts.cases.v1.ParticipantServiceGrpc;
import com.actaflow.contracts.cases.v1.PartyCapacity;
import com.actaflow.contracts.cases.v1.PartySide;
import com.actaflow.contracts.cases.v1.RemoveParticipantRequest;
import com.actaflow.contracts.cases.v1.RemoveParticipantResponse;

import io.grpc.stub.StreamObserver;

/** ParticipantService del contrato: valida la peticion y delega en el caso de uso. */
public final class ServicioParticipantesGrpc extends ParticipantServiceGrpc.ParticipantServiceImplBase {

    private final ServicioParticipantes servicio;

    public ServicioParticipantesGrpc(ServicioParticipantes servicio) {
        this.servicio = servicio;
    }

    @Override
    public void listParticipants(ListParticipantsRequest p, StreamObserver<ListParticipantsResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("case_id", p.getCaseId()).verificar();
            return servicio.listar(InterceptorSesion.actual(), UUID.fromString(p.getCaseId()));
        });
    }

    @Override
    public void inviteParticipant(InviteParticipantRequest p, StreamObserver<InviteParticipantResponse> r) {
        responder(r, () -> {
            Reglas reglas = new Reglas().uuid("case_id", p.getCaseId())
                    .correo("email", p.getEmail().strip())
                    .largo("display_name", p.getDisplayName().strip(), 2, 120)
                    .elegido("role", p.getRole(), p.getRole() != CaseRole.UNRECOGNIZED)
                    .definido("side", p.getSide() != PartySide.UNRECOGNIZED)
                    .definido("capacity", p.getCapacity() != PartyCapacity.UNRECOGNIZED)
                    .uuid("idempotency_key", p.getIdempotencyKey());
            // Regla CEL del contrato: las partes llevan lado y calidad; los demas roles no.
            boolean esParte = p.getRoleValue() == CaseRole.CASE_ROLE_PARTY_VALUE;
            boolean conLadoYCalidad = p.getSideValue() != 0 && p.getCapacityValue() != 0;
            boolean sinLadoNiCalidad = p.getSideValue() == 0 && p.getCapacityValue() == 0;
            reglas.cumple("", "invite_participant.party_fields", esParte ? conLadoYCalidad : sinLadoNiCalidad,
                    "parties need side and capacity; other roles must not set them");
            if (p.hasWrappedCaseKey()) {
                reglas.claveEnvuelta("wrapped_case_key", true, p.getWrappedCaseKey());
            }
            reglas.verificar();
            return servicio.invitar(InterceptorSesion.actual(), p);
        });
    }

    @Override
    public void grantCaseKey(GrantCaseKeyRequest p, StreamObserver<GrantCaseKeyResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("case_id", p.getCaseId()).uuid("participant_id", p.getParticipantId())
                    .claveEnvuelta("wrapped_case_key", p.hasWrappedCaseKey(), p.getWrappedCaseKey())
                    .verificar();
            return servicio.darClave(InterceptorSesion.actual(), p);
        });
    }

    @Override
    public void changeParticipantRole(ChangeParticipantRoleRequest p,
                                      StreamObserver<ChangeParticipantRoleResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("case_id", p.getCaseId()).uuid("participant_id", p.getParticipantId())
                    .elegido("role", p.getRole(), p.getRole() != CaseRole.UNRECOGNIZED)
                    .verificar();
            return ChangeParticipantRoleResponse.newBuilder().setParticipant(servicio.cambiarRol(
                    InterceptorSesion.actual(), UUID.fromString(p.getCaseId()), UUID.fromString(p.getParticipantId()),
                    p.getRole())).build();
        });
    }

    @Override
    public void removeParticipant(RemoveParticipantRequest p, StreamObserver<RemoveParticipantResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("case_id", p.getCaseId()).uuid("participant_id", p.getParticipantId()).verificar();
            servicio.retirar(InterceptorSesion.actual(), UUID.fromString(p.getCaseId()),
                    UUID.fromString(p.getParticipantId()));
            return RemoveParticipantResponse.getDefaultInstance();
        });
    }
}

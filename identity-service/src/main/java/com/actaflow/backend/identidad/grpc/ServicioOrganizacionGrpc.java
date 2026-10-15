package com.actaflow.backend.identidad.grpc;

import static com.actaflow.backend.identidad.grpc.Respuestas.responder;

import java.util.Optional;
import java.util.UUID;

import com.actaflow.backend.identidad.aplicacion.ServicioOrganizacion;
import com.actaflow.backend.identidad.dominio.Rol;
import com.actaflow.contracts.identity.v1.ChangeMemberRoleRequest;
import com.actaflow.contracts.identity.v1.ChangeMemberRoleResponse;
import com.actaflow.contracts.identity.v1.GetOrganizationRequest;
import com.actaflow.contracts.identity.v1.GetOrganizationResponse;
import com.actaflow.contracts.identity.v1.InviteMemberRequest;
import com.actaflow.contracts.identity.v1.InviteMemberResponse;
import com.actaflow.contracts.identity.v1.ListMembersRequest;
import com.actaflow.contracts.identity.v1.ListMembersResponse;
import com.actaflow.contracts.identity.v1.OrganizationServiceGrpc;
import com.actaflow.contracts.identity.v1.PlatformRole;
import com.actaflow.contracts.identity.v1.SetMemberActiveRequest;
import com.actaflow.contracts.identity.v1.SetMemberActiveResponse;

import io.grpc.stub.StreamObserver;

/** {@code actaflow.identity.v1.OrganizationService}. */
public final class ServicioOrganizacionGrpc extends OrganizationServiceGrpc.OrganizationServiceImplBase {

    private final ServicioOrganizacion servicio;

    public ServicioOrganizacionGrpc(ServicioOrganizacion servicio) {
        this.servicio = servicio;
    }

    @Override
    public void getOrganization(GetOrganizationRequest p, StreamObserver<GetOrganizationResponse> r) {
        responder(r, () -> GetOrganizationResponse.newBuilder()
                .setOrganization(servicio.organizacion(Sesion.exigir())).build());
    }

    @Override
    public void listMembers(ListMembersRequest p, StreamObserver<ListMembersResponse> r) {
        responder(r, () -> {
            var principal = Sesion.exigir();
            new Reglas().requerido("page", p.hasPage())
                    .cumple("page.page_size", "int32.gte", p.getPage().getPageSize() >= 1,
                            "value must be greater than or equal to 1")
                    .cumple("page.page_size", "int32.lte", p.getPage().getPageSize() <= 100,
                            "value must be less than or equal to 100")
                    .largo("page.page_token", p.getPage().getPageToken(), 0, 512)
                    .cumple("role", "enum.defined_only", p.getRole() != PlatformRole.UNRECOGNIZED,
                            "value must be one of the defined enum values")
                    .verificar();
            Optional<Rol> rol = p.getRole() == PlatformRole.PLATFORM_ROLE_UNSPECIFIED
                    ? Optional.empty() : Optional.of(Rol.desde(p.getRole()));
            return servicio.miembros(principal, p.getPage().getPageSize(), p.getPage().getPageToken(), rol);
        });
    }

    @Override
    public void inviteMember(InviteMemberRequest p, StreamObserver<InviteMemberResponse> r) {
        responder(r, () -> {
            var principal = Sesion.exigir();
            reglasRol(new Reglas().correo("email", p.getEmail()), p.getRole())
                    .uuid("idempotency_key", p.getIdempotencyKey()).verificar();
            return servicio.invitar(principal, p.getEmail(), Rol.desde(p.getRole()),
                    UUID.fromString(p.getIdempotencyKey()));
        });
    }

    @Override
    public void changeMemberRole(ChangeMemberRoleRequest p, StreamObserver<ChangeMemberRoleResponse> r) {
        responder(r, () -> {
            var principal = Sesion.exigir();
            reglasRol(new Reglas().uuid("account_id", p.getAccountId()), p.getRole()).verificar();
            return ChangeMemberRoleResponse.newBuilder().setMember(servicio.cambiarRol(principal,
                    UUID.fromString(p.getAccountId()), Rol.desde(p.getRole()))).build();
        });
    }

    @Override
    public void setMemberActive(SetMemberActiveRequest p, StreamObserver<SetMemberActiveResponse> r) {
        responder(r, () -> {
            var principal = Sesion.exigir();
            new Reglas().uuid("account_id", p.getAccountId()).verificar();
            return SetMemberActiveResponse.newBuilder().setMember(servicio.activar(principal,
                    UUID.fromString(p.getAccountId()), p.getActive())).build();
        });
    }

    private static Reglas reglasRol(Reglas reglas, PlatformRole rol) {
        return reglas
                .cumple("role", "enum.defined_only", rol != PlatformRole.UNRECOGNIZED,
                        "value must be one of the defined enum values")
                .cumple("role", "enum.not_in", rol != PlatformRole.PLATFORM_ROLE_UNSPECIFIED,
                        "value must not be in list [0]");
    }
}

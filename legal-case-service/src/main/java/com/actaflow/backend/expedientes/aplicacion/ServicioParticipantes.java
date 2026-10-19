package com.actaflow.backend.expedientes.aplicacion;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.actaflow.backend.expedientes.aplicacion.Filas.Fila;
import com.actaflow.backend.expedientes.seguridad.Usuario;
import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.backend.plataforma.rpc.FalloRpc;
import com.actaflow.backend.plataforma.rpc.FalloRpc.Tipo;
import com.actaflow.contracts.cases.v1.CaseEventKind;
import com.actaflow.contracts.cases.v1.CaseRole;
import com.actaflow.contracts.cases.v1.GrantCaseKeyRequest;
import com.actaflow.contracts.cases.v1.GrantCaseKeyResponse;
import com.actaflow.contracts.cases.v1.InviteParticipantRequest;
import com.actaflow.contracts.cases.v1.InviteParticipantResponse;
import com.actaflow.contracts.cases.v1.ListParticipantsResponse;
import com.actaflow.contracts.cases.v1.Participant;

/**
 * Personas de un caso y reparto de la clave del caso. El servidor nunca tiene la clave en claro:
 * un participante que ya la tiene la envuelve en su navegador para la clave publica del nuevo.
 */
public final class ServicioParticipantes {

    private final Bd bd;
    private final Clock reloj;

    public ServicioParticipantes(Bd bd, Clock reloj) {
        this.bd = bd;
        this.reloj = reloj;
    }

    public ListParticipantsResponse listar(Usuario usuario, UUID casoId) {
        return bd.transaccion(c -> {
            ServicioCasos.vincular(c, usuario, reloj.instant());
            ServicioCasos.miembro(c, casoId, usuario, false);
            ListParticipantsResponse.Builder respuesta = ListParticipantsResponse.newBuilder();
            Filas.participantes(c, casoId).forEach(p -> respuesta.addParticipants(Filas.contrato(p)));
            return respuesta.build();
        });
    }

    /**
     * Invita a una persona por correo. Si aun no tiene cuenta (o no ha entrado), queda pendiente y se
     * vincula sola cuando entre; la clave del caso se le da despues con GrantCaseKey.
     */
    public InviteParticipantResponse invitar(Usuario usuario, InviteParticipantRequest peticion) {
        UUID casoId = UUID.fromString(peticion.getCaseId());
        Instant ahora = reloj.instant();
        String correo = peticion.getEmail().strip().toLowerCase(Locale.ROOT);
        return bd.transaccion(c -> Idempotencia.una(c, usuario, UUID.fromString(peticion.getIdempotencyKey()),
                "InviteParticipant", InviteParticipantResponse.parser(), ahora, () -> {
                    ServicioCasos.vincular(c, usuario, ahora);
                    Fila yo = ServicioCasos.miembro(c, casoId, usuario, false);
                    ServicioCasos.exigirConciliador(yo);
                    ServicioCasos.exigirAbierto(ServicioCasos.bloquear(c, casoId));
                    boolean repetido = Bd.uno(c, "select 1 as uno from participante where caso_id = ?"
                            + " and lower(email) = ?", f -> true, casoId, correo).isPresent();
                    if (repetido) {
                        throw new FalloRpc(Tipo.YA_EXISTE, "Esa persona ya esta en el caso");
                    }
                    UUID id = UUID.randomUUID();
                    Bd.ejecutar(c, "insert into participante (id, caso_id, email, display_name, role, side, capacity,"
                            + " clave_envuelta, invited_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, casoId, correo,
                            peticion.getDisplayName().strip(), Filas.texto(peticion.getRole()),
                            Filas.texto(peticion.getSide()), Filas.texto(peticion.getCapacity()),
                            peticion.hasWrappedCaseKey() ? peticion.getWrappedCaseKey().toByteArray() : null, ahora);
                    ServicioCasos.evento(c, casoId, CaseEventKind.CASE_EVENT_KIND_PARTICIPANT_ADDED, yo.id(),
                            yo.nombre(), ahora);
                    return InviteParticipantResponse.newBuilder().setParticipant(porId(c, casoId, id)).build();
                }));
    }

    /**
     * Entrega la clave del caso (envuelta para su clave publica) a un participante que ya tiene cuenta.
     * Cualquier participante que tenga la clave puede hacerlo, asi el acceso no depende de que el
     * conciliador este conectado.
     */
    public GrantCaseKeyResponse darClave(Usuario usuario, GrantCaseKeyRequest peticion) {
        UUID casoId = UUID.fromString(peticion.getCaseId());
        UUID participanteId = UUID.fromString(peticion.getParticipantId());
        return bd.transaccion(c -> {
            ServicioCasos.vincular(c, usuario, reloj.instant());
            Fila yo = ServicioCasos.miembro(c, casoId, usuario, false);
            if (!yo.tieneClave()) {
                throw new FalloRpc(Tipo.PRECONDICION, "Aun no tienes la clave de este caso");
            }
            ServicioCasos.exigirAbierto(ServicioCasos.bloquear(c, casoId));
            Fila destino = objetivo(c, casoId, participanteId);
            if (destino.cuentaId() == null) {
                throw new FalloRpc(Tipo.PRECONDICION, "Esa persona aun no ha entrado con su cuenta");
            }
            Bd.ejecutar(c, "update participante set clave_envuelta = ? where id = ?",
                    peticion.getWrappedCaseKey().toByteArray(), participanteId);
            Bd.ejecutar(c, "update caso set last_activity_at = ? where id = ?", reloj.instant(), casoId);
            return GrantCaseKeyResponse.newBuilder().setParticipant(porId(c, casoId, participanteId)).build();
        });
    }

    /**
     * Cambia el rol entre conciliador y asistente. Las partes no cambian de rol (necesitarian lado y
     * calidad, que esta peticion no trae): se retiran y se invitan de nuevo.
     */
    public Participant cambiarRol(Usuario usuario, UUID casoId, UUID participanteId, CaseRole rol) {
        return bd.transaccion(c -> {
            ServicioCasos.vincular(c, usuario, reloj.instant());
            Fila yo = ServicioCasos.miembro(c, casoId, usuario, false);
            ServicioCasos.exigirConciliador(yo);
            ServicioCasos.exigirAbierto(ServicioCasos.bloquear(c, casoId));
            Fila destino = objetivo(c, casoId, participanteId);
            if (destino.rol() == CaseRole.CASE_ROLE_PARTY || rol == CaseRole.CASE_ROLE_PARTY) {
                throw new FalloRpc(Tipo.PRECONDICION,
                        "Las partes no cambian de rol: retirala e invitala de nuevo con su lado y calidad");
            }
            if (destino.rol() == CaseRole.CASE_ROLE_CONCILIATOR && rol != CaseRole.CASE_ROLE_CONCILIATOR) {
                exigirOtroConciliador(c, casoId);
            }
            Bd.ejecutar(c, "update participante set role = ? where id = ?", Filas.texto(rol), participanteId);
            return porId(c, casoId, participanteId);
        });
    }

    /** Retira a alguien del caso y borra su clave envuelta. Lo que ya descargo no se puede recuperar. */
    public void retirar(Usuario usuario, UUID casoId, UUID participanteId) {
        bd.transaccion(c -> {
            ServicioCasos.vincular(c, usuario, reloj.instant());
            Fila yo = ServicioCasos.miembro(c, casoId, usuario, false);
            ServicioCasos.exigirConciliador(yo);
            ServicioCasos.exigirAbierto(ServicioCasos.bloquear(c, casoId));
            Fila destino = objetivo(c, casoId, participanteId);
            if (destino.rol() == CaseRole.CASE_ROLE_CONCILIATOR) {
                exigirOtroConciliador(c, casoId);
            }
            Bd.ejecutar(c, "delete from participante where id = ?", participanteId);
            Bd.ejecutar(c, "update caso set last_activity_at = ? where id = ?", reloj.instant(), casoId);
            return null;
        });
    }

    private static Fila objetivo(Connection c, UUID casoId, UUID participanteId) throws SQLException {
        return Bd.uno(c, "select " + Filas.COLUMNAS_PARTICIPANTE + " from participante where caso_id = ? and id = ?"
                + " for update", Filas::participante, casoId, participanteId)
                .orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "Participante no encontrado en este caso"));
    }

    private static Participant porId(Connection c, UUID casoId, UUID participanteId) throws SQLException {
        return Filas.contrato(objetivo(c, casoId, participanteId));
    }

    private static void exigirOtroConciliador(Connection c, UUID casoId) throws SQLException {
        int conciliadores = Bd.uno(c, "select count(*) as n from participante where caso_id = ?"
                + " and role = 'CONCILIATOR'", f -> f.getInt("n"), casoId).orElse(0);
        if (conciliadores <= 1) {
            throw new FalloRpc(Tipo.PRECONDICION, "El caso debe conservar al menos un conciliador");
        }
    }
}

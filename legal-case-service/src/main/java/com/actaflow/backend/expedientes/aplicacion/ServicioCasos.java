package com.actaflow.backend.expedientes.aplicacion;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.actaflow.backend.expedientes.aplicacion.Filas.Fila;
import com.actaflow.backend.expedientes.seguridad.Usuario;
import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.backend.plataforma.rpc.FalloRpc;
import com.actaflow.backend.plataforma.rpc.FalloRpc.Tipo;
import com.actaflow.contracts.cases.v1.CaseEvent;
import com.actaflow.contracts.cases.v1.CaseEventKind;
import com.actaflow.contracts.cases.v1.CaseMembership;
import com.actaflow.contracts.cases.v1.CaseRole;
import com.actaflow.contracts.cases.v1.CaseStatus;
import com.actaflow.contracts.cases.v1.CaseSummary;
import com.actaflow.contracts.cases.v1.ChangeCaseStatusRequest;
import com.actaflow.contracts.cases.v1.ChangeCaseStatusResponse;
import com.actaflow.contracts.cases.v1.CreateCaseRequest;
import com.actaflow.contracts.cases.v1.CreateCaseResponse;
import com.actaflow.contracts.cases.v1.GetCaseResponse;
import com.actaflow.contracts.cases.v1.LegalCase;
import com.actaflow.contracts.cases.v1.ListCaseEventsResponse;
import com.actaflow.contracts.cases.v1.ListCasesResponse;
import com.actaflow.contracts.cases.v1.UpdateCaseRequest;
import com.actaflow.contracts.cases.v1.UpdateCaseResponse;
import com.actaflow.contracts.common.v1.PageInfo;

/**
 * Expedientes. Regla de acceso de TODAS las operaciones: quien llama debe ser participante del caso;
 * si no lo es, la respuesta es NOT_FOUND (nunca PERMISSION_DENIED), asi no se revela que el caso
 * existe. Los administradores de la organizacion no tienen acceso especial al contenido.
 */
public final class ServicioCasos {

    /**
     * Transiciones de estado permitidas. Abierto -> en audiencia -> acuerdo / no acuerdo -> archivado;
     * una audiencia se puede suspender (volver a abierto) y un no acuerdo se puede reabrir.
     */
    static final Map<CaseStatus, Set<CaseStatus>> TRANSICIONES = Map.of(
            CaseStatus.CASE_STATUS_OPEN, EnumSet.of(CaseStatus.CASE_STATUS_IN_HEARING, CaseStatus.CASE_STATUS_ARCHIVED),
            CaseStatus.CASE_STATUS_IN_HEARING, EnumSet.of(CaseStatus.CASE_STATUS_OPEN, CaseStatus.CASE_STATUS_AGREEMENT,
                    CaseStatus.CASE_STATUS_NO_AGREEMENT),
            CaseStatus.CASE_STATUS_AGREEMENT, EnumSet.of(CaseStatus.CASE_STATUS_ARCHIVED),
            CaseStatus.CASE_STATUS_NO_AGREEMENT,
            EnumSet.of(CaseStatus.CASE_STATUS_OPEN, CaseStatus.CASE_STATUS_ARCHIVED),
            CaseStatus.CASE_STATUS_ARCHIVED, EnumSet.noneOf(CaseStatus.class));

    private final Bd bd;
    private final Clock reloj;

    public ServicioCasos(Bd bd, Clock reloj) {
        this.bd = bd;
        this.reloj = reloj;
    }

    public CreateCaseResponse crear(Usuario usuario, CreateCaseRequest peticion) {
        if (usuario.esExterno()) {
            throw new FalloRpc(Tipo.SIN_PERMISO, "Las cuentas externas no crean expedientes");
        }
        if (usuario.correo().isEmpty()) {
            throw new FalloRpc(Tipo.NO_AUTENTICADO, "Vuelve a ingresar para continuar");
        }
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> Idempotencia.una(c, usuario, UUID.fromString(peticion.getIdempotencyKey()),
                "CreateCase", CreateCaseResponse.parser(), ahora, () -> {
                    boolean repetido = Bd.uno(c,
                            "select 1 as uno from caso where organization_id = ? and case_number = ?",
                            f -> true, usuario.organizacionId(), peticion.getCaseNumber()).isPresent();
                    if (repetido) {
                        throw new FalloRpc(Tipo.YA_EXISTE, "Ya existe un expediente con ese radicado");
                    }
                    UUID casoId = UUID.randomUUID();
                    Bd.ejecutar(c, "insert into caso (id, organization_id, case_number, kind, status, detalles,"
                            + " scheduled_hearing_at, revision, created_by, created_at, last_activity_at)"
                            + " values (?, ?, ?, ?, 'OPEN', ?, ?, 1, ?, ?, ?)", casoId, usuario.organizacionId(),
                            peticion.getCaseNumber(), Filas.texto(peticion.getKind()),
                            peticion.getDetails().toByteArray(),
                            peticion.hasScheduledHearingAt() ? Filas.instante(peticion.getScheduledHearingAt()) : null,
                            usuario.cuentaId(), ahora, ahora);
                    UUID participanteId = UUID.randomUUID();
                    Bd.ejecutar(c, "insert into participante (id, caso_id, account_id, email, display_name, role,"
                            + " clave_envuelta, invited_at, joined_at) values (?, ?, ?, ?, ?, 'CONCILIATOR', ?, ?, ?)",
                            participanteId, casoId, usuario.cuentaId(), usuario.correo(), nombre(usuario),
                            peticion.getWrappedCaseKey().toByteArray(), ahora, ahora);
                    evento(c, casoId, CaseEventKind.CASE_EVENT_KIND_CREATED, participanteId, nombre(usuario), ahora);
                    return CreateCaseResponse.newBuilder().setLegalCase(Filas.casoPorId(c, casoId))
                            .setMembership(membresia(Filas.participanteDe(c, casoId, usuario.cuentaId(), false)
                                    .orElseThrow()))
                            .build();
                }));
    }

    public GetCaseResponse obtener(Usuario usuario, UUID casoId) {
        return bd.transaccion(c -> {
            vincular(c, usuario, reloj.instant());
            Fila yo = miembro(c, casoId, usuario, false);
            return GetCaseResponse.newBuilder().setLegalCase(Filas.casoPorId(c, casoId)).setMembership(membresia(yo))
                    .build();
        });
    }

    /** Casos donde participa quien llama, actividad mas reciente primero. */
    public ListCasesResponse listar(Usuario usuario, int tamano, String token, CaseStatus filtro) {
        int desde = Filas.desplazamiento(token);
        String estado = filtro == CaseStatus.CASE_STATUS_UNSPECIFIED ? null : Filas.texto(filtro);
        return bd.transaccion(c -> {
            vincular(c, usuario, reloj.instant());
            record Renglon(LegalCase caso, CaseRole rol) {
            }
            List<Renglon> filas = Bd.varios(c, "select " + Filas.COLUMNAS_CASO + ", p.role as mi_rol from caso c"
                    + " join participante p on p.caso_id = c.id and p.account_id = ?"
                    + " where cast(? as varchar) is null or c.status = cast(? as varchar)"
                    + " order by c.last_activity_at desc, c.id limit ? offset ?",
                    f -> new Renglon(Filas.caso(f), Filas.rol(f.getString("mi_rol"))),
                    usuario.cuentaId(), estado, estado, tamano + 1, desde);
            ListCasesResponse.Builder respuesta = ListCasesResponse.newBuilder();
            filas.stream().limit(tamano).forEach(r -> respuesta.addCases(
                    CaseSummary.newBuilder().setLegalCase(r.caso()).setRole(r.rol())));
            String siguiente = filas.size() > tamano ? Filas.token(desde + tamano) : "";
            return respuesta.setPageInfo(PageInfo.newBuilder().setNextPageToken(siguiente)).build();
        });
    }

    /** Cambia el contenido cifrado o la fecha de audiencia, con concurrencia optimista. */
    public UpdateCaseResponse actualizar(Usuario usuario, UpdateCaseRequest peticion) {
        if (!peticion.hasDetails() && !peticion.hasScheduledHearingAt()) {
            throw new FalloRpc(Tipo.ARGUMENTO_INVALIDO, "No hay cambios: envia details o scheduled_hearing_at",
                    List.of(new FalloRpc.Violacion("details", "update_case.changes",
                            "at least one of details or scheduled_hearing_at is required")));
        }
        UUID casoId = UUID.fromString(peticion.getCaseId());
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> Idempotencia.una(c, usuario, UUID.fromString(peticion.getIdempotencyKey()),
                "UpdateCase", UpdateCaseResponse.parser(), ahora, () -> {
                    vincular(c, usuario, reloj.instant());
                    Fila yo = miembro(c, casoId, usuario, false);
                    if (yo.rol() == CaseRole.CASE_ROLE_PARTY) {
                        throw new FalloRpc(Tipo.SIN_PERMISO, "Las partes no editan el expediente");
                    }
                    LegalCase actual = bloquear(c, casoId);
                    exigirAbierto(actual);
                    if (actual.getRevision() != peticion.getExpectedRevision()) {
                        throw new FalloRpc(Tipo.ABORTADO,
                                "Alguien mas cambio el expediente. Recarga y vuelve a intentarlo");
                    }
                    Bd.ejecutar(c, "update caso set detalles = coalesce(?, detalles),"
                            + " scheduled_hearing_at = coalesce(?, scheduled_hearing_at),"
                            + " revision = revision + 1, last_activity_at = ? where id = ?",
                            peticion.hasDetails() ? peticion.getDetails().toByteArray() : null,
                            peticion.hasScheduledHearingAt() ? Filas.instante(peticion.getScheduledHearingAt()) : null,
                            ahora, casoId);
                    return UpdateCaseResponse.newBuilder().setLegalCase(Filas.casoPorId(c, casoId)).build();
                }));
    }

    /** Solo conciliadores. Las transiciones invalidas fallan con FAILED_PRECONDITION. */
    public ChangeCaseStatusResponse cambiarEstado(Usuario usuario, ChangeCaseStatusRequest peticion) {
        UUID casoId = UUID.fromString(peticion.getCaseId());
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> Idempotencia.una(c, usuario, UUID.fromString(peticion.getIdempotencyKey()),
                "ChangeCaseStatus", ChangeCaseStatusResponse.parser(), ahora, () -> {
                    vincular(c, usuario, reloj.instant());
                    Fila yo = miembro(c, casoId, usuario, false);
                    exigirConciliador(yo);
                    LegalCase actual = bloquear(c, casoId);
                    CaseStatus destino = peticion.getStatus();
                    if (!TRANSICIONES.get(actual.getStatus()).contains(destino)) {
                        throw new FalloRpc(Tipo.PRECONDICION, "No se puede pasar de " + Filas.texto(actual.getStatus())
                                + " a " + Filas.texto(destino));
                    }
                    Bd.ejecutar(c, "update caso set status = ?, revision = revision + 1, last_activity_at = ?"
                            + " where id = ?", Filas.texto(destino), ahora, casoId);
                    evento(c, casoId, CaseEventKind.CASE_EVENT_KIND_STATUS_CHANGED, yo.id(), yo.nombre(), ahora);
                    if (destino == CaseStatus.CASE_STATUS_IN_HEARING) {
                        evento(c, casoId, CaseEventKind.CASE_EVENT_KIND_HEARING_STARTED, yo.id(), yo.nombre(), ahora);
                    } else if (actual.getStatus() == CaseStatus.CASE_STATUS_IN_HEARING) {
                        evento(c, casoId, CaseEventKind.CASE_EVENT_KIND_HEARING_ENDED, yo.id(), yo.nombre(), ahora);
                    }
                    return ChangeCaseStatusResponse.newBuilder().setLegalCase(Filas.casoPorId(c, casoId)).build();
                }));
    }

    /** Linea de tiempo (solo metadatos), lo mas reciente primero. */
    public ListCaseEventsResponse eventos(Usuario usuario, UUID casoId, int tamano, String token) {
        int desde = Filas.desplazamiento(token);
        return bd.transaccion(c -> {
            vincular(c, usuario, reloj.instant());
            miembro(c, casoId, usuario, false);
            List<CaseEvent> filas = Bd.varios(c, "select id, kind, occurred_at, actor_participant_id,"
                    + " actor_display_name from evento_caso where caso_id = ?"
                    + " order by occurred_at desc, id desc limit ? offset ?",
                    f -> {
                        CaseEvent.Builder evento = CaseEvent.newBuilder()
                                .setId(Bd.uuid(f, "id").toString())
                                .setKind(CaseEventKind.valueOf("CASE_EVENT_KIND_" + f.getString("kind")))
                                .setOccurredAt(Filas.marca(Bd.instante(f, "occurred_at")))
                                .setActorDisplayName(f.getString("actor_display_name"));
                        UUID actor = Bd.uuid(f, "actor_participant_id");
                        return evento.setActorParticipantId(actor == null ? "" : actor.toString()).build();
                    }, casoId, tamano + 1, desde);
            ListCaseEventsResponse.Builder respuesta = ListCaseEventsResponse.newBuilder();
            filas.stream().limit(tamano).forEach(respuesta::addEvents);
            String siguiente = filas.size() > tamano ? Filas.token(desde + tamano) : "";
            return respuesta.setPageInfo(PageInfo.newBuilder().setNextPageToken(siguiente)).build();
        });
    }

    // ------------------------------------------------------------------ reglas compartidas

    /**
     * Vincula las invitaciones pendientes de este correo con la cuenta: la primera vez que la persona
     * entra con su cuenta, los casos a los que la invitaron aparecen solos.
     */
    static void vincular(Connection c, Usuario usuario, Instant ahora) throws SQLException {
        if (usuario.correo().isEmpty()) {
            return;
        }
        Bd.ejecutar(c, "update participante p set account_id = ?, display_name = coalesce(nullif(?, ''), display_name),"
                + " joined_at = ? where p.account_id is null and lower(p.email) = ?"
                + " and exists (select 1 from caso c where c.id = p.caso_id and c.organization_id = ?)"
                + " and not exists (select 1 from participante o where o.caso_id = p.caso_id and o.account_id = ?)",
                usuario.cuentaId(), usuario.nombre(), ahora, usuario.correo(), usuario.organizacionId(),
                usuario.cuentaId());
    }

    /** Participante que llama, o NOT_FOUND: no se revela si el caso existe. */
    static Fila miembro(Connection c, UUID casoId, Usuario usuario, boolean bloquear) throws SQLException {
        return Filas.participanteDe(c, casoId, usuario.cuentaId(), bloquear)
                .orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "Expediente no encontrado"));
    }

    static void exigirConciliador(Fila yo) {
        if (yo.rol() != CaseRole.CASE_ROLE_CONCILIATOR) {
            throw new FalloRpc(Tipo.SIN_PERMISO, "Solo un conciliador del caso puede hacer esto");
        }
    }

    static void exigirAbierto(LegalCase caso) {
        if (caso.getStatus() == CaseStatus.CASE_STATUS_ARCHIVED) {
            throw new FalloRpc(Tipo.PRECONDICION, "El expediente esta archivado y es de solo lectura");
        }
    }

    /** Bloquea la fila del caso hasta el fin de la transaccion y la devuelve. */
    static LegalCase bloquear(Connection c, UUID casoId) throws SQLException {
        Bd.uno(c, "select id from caso where id = ? for update", f -> true, casoId);
        return Filas.casoPorId(c, casoId);
    }

    static void evento(Connection c, UUID casoId, CaseEventKind tipo, UUID actor, String nombre, Instant cuando)
            throws SQLException {
        Bd.ejecutar(c, "insert into evento_caso (id, caso_id, kind, occurred_at, actor_participant_id,"
                + " actor_display_name) values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), casoId,
                tipo.name().substring("CASE_EVENT_KIND_".length()), cuando, actor, nombre);
        Bd.ejecutar(c, "update caso set last_activity_at = greatest(last_activity_at, ?) where id = ?", cuando, casoId);
    }

    static CaseMembership membresia(Fila yo) {
        CaseMembership.Builder membresia = CaseMembership.newBuilder().setParticipantId(yo.id().toString())
                .setRole(yo.rol());
        if (yo.tieneClave()) {
            membresia.setWrappedCaseKey(Filas.clave(yo.claveEnvuelta()));
        }
        return membresia.build();
    }

    static String nombre(Usuario usuario) {
        String nombre = usuario.nombre() == null ? "" : usuario.nombre().strip();
        return nombre.length() >= 2 ? nombre : usuario.correo();
    }
}

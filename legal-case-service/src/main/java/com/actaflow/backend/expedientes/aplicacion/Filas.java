package com.actaflow.backend.expedientes.aplicacion;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.backend.plataforma.rpc.FalloRpc;
import com.actaflow.backend.plataforma.rpc.FalloRpc.Tipo;
import com.actaflow.contracts.cases.v1.CaseKind;
import com.actaflow.contracts.cases.v1.CaseRole;
import com.actaflow.contracts.cases.v1.CaseStatus;
import com.actaflow.contracts.cases.v1.LegalCase;
import com.actaflow.contracts.cases.v1.Participant;
import com.actaflow.contracts.cases.v1.ParticipantState;
import com.actaflow.contracts.cases.v1.PartyCapacity;
import com.actaflow.contracts.cases.v1.PartySide;
import com.actaflow.contracts.crypto.v1.EncryptedBlob;
import com.actaflow.contracts.crypto.v1.WrappedKey;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;

/**
 * Filas de la base y mensajes del contrato. Cada entidad se convierte al mensaje protobuf en UN
 * solo lugar: si el contrato cambia, el error de compilacion sale aqui.
 */
final class Filas {

    private Filas() {
    }

    // ------------------------------------------------------------- enums <-> texto de la base

    static String texto(CaseKind kind) {
        return kind.name().substring("CASE_KIND_".length());
    }

    static String texto(CaseStatus status) {
        return status.name().substring("CASE_STATUS_".length());
    }

    static String texto(CaseRole role) {
        return role.name().substring("CASE_ROLE_".length());
    }

    static String texto(PartySide side) {
        return side == PartySide.PARTY_SIDE_UNSPECIFIED ? null : side.name().substring("PARTY_SIDE_".length());
    }

    static String texto(PartyCapacity capacity) {
        return capacity == PartyCapacity.PARTY_CAPACITY_UNSPECIFIED ? null
                : capacity.name().substring("PARTY_CAPACITY_".length());
    }

    static CaseStatus estado(String texto) {
        return CaseStatus.valueOf("CASE_STATUS_" + texto);
    }

    static CaseRole rol(String texto) {
        return CaseRole.valueOf("CASE_ROLE_" + texto);
    }

    // ------------------------------------------------------------------------------- caso

    /** Columnas del caso, con el numero de participantes. */
    static final String COLUMNAS_CASO = "c.id, c.organization_id, c.case_number, c.kind, c.status, c.detalles,"
            + " c.scheduled_hearing_at, c.revision, c.created_at, c.last_activity_at,"
            + " (select count(*) from participante x where x.caso_id = c.id) as participantes";

    static LegalCase caso(ResultSet f) throws SQLException {
        LegalCase.Builder caso = LegalCase.newBuilder()
                .setId(Bd.uuid(f, "id").toString())
                .setOrganizationId(Bd.uuid(f, "organization_id").toString())
                .setCaseNumber(f.getString("case_number"))
                .setKind(CaseKind.valueOf("CASE_KIND_" + f.getString("kind")))
                .setStatus(estado(f.getString("status")))
                .setDetails(leer(f.getBytes("detalles"), EncryptedBlob.parser()))
                .setParticipantCount(f.getInt("participantes"))
                .setCreatedAt(marca(Bd.instante(f, "created_at")))
                .setLastActivityAt(marca(Bd.instante(f, "last_activity_at")))
                .setRevision(f.getLong("revision"));
        Instant audiencia = Bd.instante(f, "scheduled_hearing_at");
        if (audiencia != null) {
            caso.setScheduledHearingAt(marca(audiencia));
        }
        return caso.build();
    }

    static LegalCase casoPorId(Connection c, UUID casoId) throws SQLException {
        return Bd.uno(c, "select " + COLUMNAS_CASO + " from caso c where c.id = ?", Filas::caso, casoId)
                .orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "Expediente no encontrado"));
    }

    // ------------------------------------------------------------------------ participante

    /** Participante tal como esta en la base. */
    record Fila(UUID id, UUID casoId, UUID cuentaId, String correo, String nombre, CaseRole rol, String lado,
                String calidad, byte[] claveEnvuelta, Instant invitado, Instant unido) {

        boolean tieneClave() {
            return claveEnvuelta != null;
        }

        ParticipantState estado() {
            if (cuentaId == null) {
                return ParticipantState.PARTICIPANT_STATE_INVITED;
            }
            return claveEnvuelta == null ? ParticipantState.PARTICIPANT_STATE_AWAITING_KEY
                    : ParticipantState.PARTICIPANT_STATE_ACTIVE;
        }
    }

    static final String COLUMNAS_PARTICIPANTE = "id, caso_id, account_id, email, display_name, role, side, capacity,"
            + " clave_envuelta, invited_at, joined_at";

    static Fila participante(ResultSet f) throws SQLException {
        return new Fila(Bd.uuid(f, "id"), Bd.uuid(f, "caso_id"), Bd.uuid(f, "account_id"), f.getString("email"),
                f.getString("display_name"), rol(f.getString("role")), f.getString("side"), f.getString("capacity"),
                f.getBytes("clave_envuelta"), Bd.instante(f, "invited_at"), Bd.instante(f, "joined_at"));
    }

    static Participant contrato(Fila p) {
        Participant.Builder participante = Participant.newBuilder()
                .setId(p.id().toString())
                .setAccountId(p.cuentaId() == null ? "" : p.cuentaId().toString())
                .setDisplayName(p.nombre())
                .setEmail(p.correo())
                .setRole(p.rol())
                .setSide(p.lado() == null ? PartySide.PARTY_SIDE_UNSPECIFIED
                        : PartySide.valueOf("PARTY_SIDE_" + p.lado()))
                .setCapacity(p.calidad() == null ? PartyCapacity.PARTY_CAPACITY_UNSPECIFIED
                        : PartyCapacity.valueOf("PARTY_CAPACITY_" + p.calidad()))
                .setState(p.estado());
        if (p.unido() != null) {
            participante.setJoinedAt(marca(p.unido()));
        }
        return participante.build();
    }

    static Optional<Fila> participanteDe(Connection c, UUID casoId, UUID cuentaId, boolean bloquear)
            throws SQLException {
        return Bd.uno(c, "select " + COLUMNAS_PARTICIPANTE + " from participante where caso_id = ? and account_id = ?"
                + (bloquear ? " for update" : ""), Filas::participante, casoId, cuentaId);
    }

    static List<Fila> participantes(Connection c, UUID casoId) throws SQLException {
        return Bd.varios(c, "select " + COLUMNAS_PARTICIPANTE + " from participante where caso_id = ?"
                + " order by invited_at, id", Filas::participante, casoId);
    }

    static WrappedKey clave(byte[] bytes) {
        return leer(bytes, WrappedKey.parser());
    }

    // --------------------------------------------------------------------------- utilidades

    static Timestamp marca(Instant instante) {
        return Timestamp.newBuilder().setSeconds(instante.getEpochSecond()).setNanos(instante.getNano()).build();
    }

    static Instant instante(Timestamp marca) {
        return Instant.ofEpochSecond(marca.getSeconds(), marca.getNanos());
    }

    private static <T> T leer(byte[] bytes, com.google.protobuf.Parser<T> parser) {
        try {
            return parser.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Dato protobuf danado en la base", e);
        }
    }

    /** Pagina por desplazamiento: el token es opaco para el cliente ("o:N" en base64url). */
    static String token(int desplazamiento) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("o:" + desplazamiento).getBytes(StandardCharsets.US_ASCII));
    }

    static int desplazamiento(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            String texto = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII);
            int valor = Integer.parseInt(texto.substring(2));
            if (!texto.startsWith("o:") || valor < 0) {
                throw new NumberFormatException();
            }
            return valor;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new FalloRpc(Tipo.ARGUMENTO_INVALIDO, "page_token no valido",
                    List.of(new FalloRpc.Violacion("page.page_token", "page_token.format",
                            "value is not a token returned by this service")));
        }
    }
}

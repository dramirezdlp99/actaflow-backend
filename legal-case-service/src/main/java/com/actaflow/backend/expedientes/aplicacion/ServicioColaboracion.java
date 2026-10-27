package com.actaflow.backend.expedientes.aplicacion;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.actaflow.backend.expedientes.aplicacion.Filas.Fila;
import com.actaflow.backend.expedientes.seguridad.Usuario;
import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.backend.plataforma.rpc.FalloRpc;
import com.actaflow.backend.plataforma.rpc.FalloRpc.Tipo;
import com.actaflow.contracts.cases.v1.CaseRole;
import com.actaflow.contracts.collaboration.v1.CompactDocumentRequest;
import com.actaflow.contracts.collaboration.v1.CompactDocumentResponse;
import com.actaflow.contracts.collaboration.v1.EncryptedUpdate;
import com.actaflow.contracts.collaboration.v1.GetDocumentStateResponse;
import com.actaflow.contracts.collaboration.v1.PushUpdatesRequest;
import com.actaflow.contracts.collaboration.v1.PushUpdatesResponse;
import com.actaflow.contracts.collaboration.v1.SequencedUpdate;
import com.actaflow.contracts.crypto.v1.EncryptedBlob;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Borrador colaborativo del acta (CollaborationService). Cada navegador manda sus cambios cifrados
 * con la clave del caso; aqui solo se les da un orden total (secuencia por acta) y se guardan. Como
 * todos aplican los cambios en ese mismo orden, todos llegan al mismo borrador (registro con ultima
 * escritura por campo, ordenado por el servidor), sin que el servidor lea nada.
 *
 * <ul>
 *   <li>Editan el conciliador y los asistentes, y solo mientras el acta es borrador. Leen todos los
 *       participantes del caso.</li>
 *   <li>Idempotente por update_id: la bandeja de salida sin conexion puede reenviar sin duplicar.</li>
 *   <li>CompactDocument guarda una instantanea cifrada y borra el contenido de los cambios que cubre.</li>
 * </ul>
 */
public final class ServicioColaboracion {

    /** Cuantos cambios se devuelven por consulta; el cliente pide lo siguiente con after_sequence. */
    static final int POR_CONSULTA = 500;

    private final Bd bd;
    private final Clock reloj;

    public ServicioColaboracion(Bd bd, Clock reloj) {
        this.bd = bd;
        this.reloj = reloj;
    }

    private record Acta(UUID casoId, String estado, long ultima, Optional<byte[]> instantanea, long cubre) {
    }

    private static Acta acta(Connection c, UUID actaId, boolean bloquear) throws SQLException {
        return Bd.uno(c, "select caso_id, state, ultima_secuencia, instantanea, instantanea_secuencia from acta"
                + " where id = ?" + (bloquear ? " for update" : ""),
                f -> new Acta(Bd.uuid(f, "caso_id"), f.getString("state"), f.getLong("ultima_secuencia"),
                        Optional.ofNullable(f.getBytes("instantanea")), f.getLong("instantanea_secuencia")),
                actaId).orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "Acta no encontrada"));
    }

    private static void exigirRedactor(Fila yo) {
        if (yo.rol() != CaseRole.CASE_ROLE_CONCILIATOR && yo.rol() != CaseRole.CASE_ROLE_ASSISTANT) {
            throw new FalloRpc(Tipo.SIN_PERMISO, "Solo el conciliador y los asistentes editan el borrador");
        }
    }

    private static void exigirBorrador(Acta a) {
        if (!"DRAFT".equals(a.estado())) {
            throw new FalloRpc(Tipo.PRECONDICION, "El acta ya se cerró: el borrador no admite cambios");
        }
    }

    private static EncryptedBlob blob(byte[] bytes) {
        try {
            return EncryptedBlob.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Contenido guardado ilegible", e);
        }
    }

    public GetDocumentStateResponse estado(Usuario usuario, UUID actaId, long despuesDe) {
        return bd.transaccion(c -> {
            Acta a = acta(c, actaId, false);
            ServicioCasos.miembro(c, a.casoId(), usuario, false);
            GetDocumentStateResponse.Builder r = GetDocumentStateResponse.newBuilder();
            long desde = despuesDe;
            // Si el cliente va antes de la instantanea, la necesita: esos cambios ya no estan.
            if (despuesDe < a.cubre() && a.instantanea().isPresent()) {
                r.setSnapshot(blob(a.instantanea().get())).setSnapshotSequence(a.cubre());
                desde = a.cubre();
            }
            List<SequencedUpdate> cambios = Bd.varios(c, "select secuencia, update_id, contenido, autor_id,"
                    + " received_at from actualizacion_acta where acta_id = ? and secuencia > ?"
                    + " and contenido is not null"
                    + " order by secuencia limit " + POR_CONSULTA,
                    f -> SequencedUpdate.newBuilder().setSequence(f.getLong("secuencia"))
                            .setUpdate(EncryptedUpdate.newBuilder().setUpdateId(Bd.uuid(f, "update_id").toString())
                                    .setUpdate(blob(f.getBytes("contenido"))))
                            .setAuthorParticipantId(Bd.uuid(f, "autor_id").toString())
                            .setReceivedAt(Filas.marca(f.getTimestamp("received_at").toInstant())).build(),
                    actaId, desde);
            return r.addAllUpdates(cambios).build();
        });
    }

    public PushUpdatesResponse enviar(Usuario usuario, PushUpdatesRequest p) {
        UUID actaId = UUID.fromString(p.getMinuteId());
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> {
            Acta a = acta(c, actaId, true);
            Fila yo = ServicioCasos.miembro(c, a.casoId(), usuario, false);
            exigirRedactor(yo);
            PushUpdatesResponse.Builder r = PushUpdatesResponse.newBuilder();
            long ultima = a.ultima();
            boolean nuevos = false;
            for (EncryptedUpdate u : p.getUpdatesList()) {
                UUID id = UUID.fromString(u.getUpdateId());
                Optional<Long> ya = Bd.uno(c, "select secuencia from actualizacion_acta where acta_id = ?"
                        + " and update_id = ?", f -> f.getLong("secuencia"), actaId, id);
                if (ya.isPresent()) {
                    r.addSequences(ya.get());
                    continue;
                }
                // Lo ya enviado se reconoce aunque el acta se haya cerrado; lo nuevo, no.
                exigirBorrador(a);
                ultima += 1;
                nuevos = true;
                Bd.ejecutar(c, "insert into actualizacion_acta (acta_id, secuencia, update_id, contenido, autor_id,"
                        + " received_at) values (?, ?, ?, ?, ?, ?)", actaId, ultima, id,
                        u.getUpdate().toByteArray(), yo.id(), ahora);
                r.addSequences(ultima);
            }
            if (nuevos) {
                Bd.ejecutar(c, "update acta set ultima_secuencia = ? where id = ?", ultima, actaId);
            }
            return r.build();
        });
    }

    public CompactDocumentResponse compactar(Usuario usuario, CompactDocumentRequest p) {
        UUID actaId = UUID.fromString(p.getMinuteId());
        return bd.transaccion(c -> {
            Acta a = acta(c, actaId, true);
            exigirRedactor(ServicioCasos.miembro(c, a.casoId(), usuario, false));
            long cubre = p.getCoversSequence();
            if (cubre > a.ultima()) {
                throw new FalloRpc(Tipo.PRECONDICION, "La instantánea cubre cambios que aún no existen");
            }
            if (cubre <= a.cubre()) {
                return CompactDocumentResponse.getDefaultInstance();
            }
            Bd.ejecutar(c, "update acta set instantanea = ?, instantanea_secuencia = ? where id = ?",
                    p.getSnapshot().toByteArray(), cubre, actaId);
            // Se borra el contenido, no la fila: un reenvio tardio de un cambio ya cubierto se reconoce.
            int borrados = Bd.ejecutar(c, "update actualizacion_acta set contenido = null where acta_id = ?"
                    + " and secuencia <= ? and contenido is not null", actaId, cubre);
            return CompactDocumentResponse.newBuilder().setRemovedUpdates(borrados).build();
        });
    }
}

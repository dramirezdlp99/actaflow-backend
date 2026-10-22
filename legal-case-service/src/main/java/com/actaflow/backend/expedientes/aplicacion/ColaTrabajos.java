package com.actaflow.backend.expedientes.aplicacion;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.contracts.crypto.v1.EncryptedBlob;
import com.actaflow.contracts.crypto.v1.SealedEnvelope;
import com.google.protobuf.InvalidProtocolBufferException;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * Cola de trabajos asincronos (tabla `trabajo` de V1): transcribir cada segmento y extraer las
 * entidades. Varios procesos pueden trabajar a la vez sin pisarse ({@code FOR UPDATE SKIP LOCKED}),
 * y un trabajo que quedo a medias (el proceso murio) se retoma cuando vence su bloqueo.
 *
 * <p>La llamada al motor va FUERA de la transaccion: puede tardar decenas de segundos. Los errores
 * pasajeros (motor dormido, plazo vencido) se reintentan con espera creciente; los definitivos
 * (sobre ilegible, audio invalido) marcan el segmento como fallido de inmediato.
 */
public final class ColaTrabajos {

    private static final Duration BLOQUEO = Duration.ofMinutes(5);
    private static final Set<Status.Code> PASAJEROS = Set.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED,
            Status.Code.RESOURCE_EXHAUSTED, Status.Code.INTERNAL, Status.Code.UNKNOWN, Status.Code.ABORTED,
            Status.Code.FAILED_PRECONDITION);

    private final Bd bd;
    private final Clock reloj;
    private final MotorIA motor;

    public ColaTrabajos(Bd bd, Clock reloj, MotorIA motor) {
        this.bd = bd;
        this.reloj = reloj;
        this.motor = motor;
    }

    static void encolar(Connection c, String tipo, UUID recurso, Instant ahora) throws SQLException {
        Bd.ejecutar(c, "insert into trabajo (tipo, clave_idempotencia, disponible_en, creado_en, actualizado_en)"
                + " values (?, ?, ?, ?, ?) on conflict (tipo, clave_idempotencia) do nothing", tipo, recurso.toString(),
                ahora, ahora, ahora);
    }

    private record Trabajo(UUID id, String tipo, UUID recurso, int intentos, int maximo) {
    }

    /** Procesa trabajos hasta vaciar la cola (o hasta `limite`). Devuelve cuantos proceso. */
    public int procesarPendientes(int limite) {
        int hechos = 0;
        while (hechos < limite) {
            Optional<Trabajo> trabajo = reclamar();
            if (trabajo.isEmpty()) {
                break;
            }
            procesar(trabajo.get());
            hechos++;
        }
        return hechos;
    }

    private Optional<Trabajo> reclamar() {
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> Bd.uno(c, "update trabajo set estado = 'EN_PROCESO', intentos = intentos + 1,"
                + " bloqueado_hasta = ?, actualizado_en = ? where id = (select id from trabajo"
                + " where (estado = 'PENDIENTE' and disponible_en <= ?)"
                + " or (estado = 'EN_PROCESO' and bloqueado_hasta < ?)"
                + " order by disponible_en limit 1 for update skip locked)"
                + " returning id, tipo, clave_idempotencia, intentos, max_intentos",
                f -> new Trabajo(Bd.uuid(f, "id"), f.getString("tipo"),
                        UUID.fromString(f.getString("clave_idempotencia")), f.getInt("intentos"),
                        f.getInt("max_intentos")),
                ahora.plus(BLOQUEO), ahora, ahora, ahora));
    }

    private void procesar(Trabajo trabajo) {
        boolean esTranscripcion = ServicioAudiencias.TRANSCRIBIR.equals(trabajo.tipo());
        String tabla = esTranscripcion ? "segmento_audio" : "extraccion";
        String columnaEntrada = esTranscripcion ? "audio" : "entrada";
        record Pendiente(UUID audienciaId, UUID casoId, byte[] entrada) {
        }
        Instant inicio = reloj.instant();
        Optional<Pendiente> pendiente = bd.transaccion(c -> {
            Optional<Pendiente> fila = Bd.uno(c, "select t.audiencia_id, a.caso_id, t." + columnaEntrada
                    + " as entrada from " + tabla + " t join audiencia a on a.id = t.audiencia_id"
                    + " where t.id = ? and t.state in ('QUEUED', 'PROCESSING')",
                    f -> new Pendiente(Bd.uuid(f, "audiencia_id"), Bd.uuid(f, "caso_id"), f.getBytes("entrada")),
                    trabajo.recurso());
            if (fila.isPresent() && fila.get().entrada() != null) {
                cambiarEstado(c, tabla, trabajo.recurso(), fila.get().audienciaId(), "PROCESSING", inicio);
            }
            return fila;
        });
        if (pendiente.isEmpty() || pendiente.get().entrada() == null) {
            terminarTrabajo(trabajo, "COMPLETADO", null, null);
            return;
        }
        Pendiente p = pendiente.get();
        EncryptedBlob resultado;
        try {
            SealedEnvelope sobre = SealedEnvelope.parseFrom(p.entrada());
            resultado = esTranscripcion
                    ? motor.transcribir(p.casoId().toString(), trabajo.recurso().toString(), sobre)
                    : motor.extraer(p.casoId().toString(), trabajo.recurso().toString(), sobre);
        } catch (InvalidProtocolBufferException e) {
            fallar(trabajo, tabla, p.audienciaId(), "Sobre sellado ilegible");
            return;
        } catch (StatusRuntimeException e) {
            Status.Code codigo = e.getStatus().getCode();
            String error = codigo + ": " + String.valueOf(e.getStatus().getDescription());
            if (PASAJEROS.contains(codigo) && trabajo.intentos() < trabajo.maximo()) {
                // 10 s, 20 s, 40 s, 80 s...: el motor de Render Free puede tardar en despertar.
                Duration espera = Duration.ofSeconds(10L << Math.min(trabajo.intentos() - 1, 6));
                terminarTrabajo(trabajo, "PENDIENTE", error, reloj.instant().plus(espera));
            } else {
                fallar(trabajo, tabla, p.audienciaId(), error);
            }
            return;
        }
        Instant fin = reloj.instant();
        bd.transaccion(c -> {
            String columnaResultado = esTranscripcion ? "transcripcion" : "propuesta";
            String extra = esTranscripcion ? "" : ", updated_at = ?";
            Object[] parametros = esTranscripcion
                    ? new Object[] {resultado.toByteArray(), trabajo.recurso()}
                    : new Object[] {resultado.toByteArray(), fin, trabajo.recurso()};
            // El audio sellado ya no hace falta: se borra en cuanto hay transcripcion.
            Bd.ejecutar(c, "update " + tabla + " set " + columnaResultado + " = ?, state = 'DONE', " + columnaEntrada
                    + " = null" + extra + " where id = ?", parametros);
            ServicioAudiencias.eventoAudiencia(c, p.audienciaId(), esTranscripcion ? "SEGMENT" : "EXTRACTION",
                    trabajo.recurso(), fin);
            return null;
        });
        terminarTrabajo(trabajo, "COMPLETADO", null, null);
    }

    private void fallar(Trabajo trabajo, String tabla, UUID audienciaId, String error) {
        Instant ahora = reloj.instant();
        String columnaEntrada = "segmento_audio".equals(tabla) ? "audio" : "entrada";
        bd.transaccion(c -> {
            Bd.ejecutar(c, "update " + tabla + " set state = 'FAILED', " + columnaEntrada + " = null where id = ?",
                    trabajo.recurso());
            ServicioAudiencias.eventoAudiencia(c, audienciaId,
                    "segmento_audio".equals(tabla) ? "SEGMENT" : "EXTRACTION", trabajo.recurso(), ahora);
            return null;
        });
        terminarTrabajo(trabajo, "FALLIDO", error, null);
    }

    private void cambiarEstado(Connection c, String tabla, UUID id, UUID audienciaId, String estado, Instant ahora)
            throws SQLException {
        int cambiadas = Bd.ejecutar(c, "update " + tabla + " set state = ? where id = ? and state <> ?", estado, id,
                estado);
        if (cambiadas > 0) {
            ServicioAudiencias.eventoAudiencia(c, audienciaId,
                    "segmento_audio".equals(tabla) ? "SEGMENT" : "EXTRACTION", id, ahora);
        }
    }

    private void terminarTrabajo(Trabajo trabajo, String estado, String error, Instant disponible) {
        Instant ahora = reloj.instant();
        bd.transaccion(c -> Bd.ejecutar(c, "update trabajo set estado = ?, ultimo_error = ?, bloqueado_hasta = null,"
                + " disponible_en = coalesce(?, disponible_en), actualizado_en = ? where id = ?", estado,
                error == null ? null : error.substring(0, Math.min(error.length(), 500)), disponible, ahora,
                trabajo.id()));
    }
}

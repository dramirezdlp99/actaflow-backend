package com.actaflow.backend.expedientes.aplicacion;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.actaflow.backend.expedientes.seguridad.Usuario;
import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.backend.plataforma.rpc.FalloRpc;
import com.actaflow.backend.plataforma.rpc.FalloRpc.Tipo;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;

/**
 * Escrituras idempotentes: la misma clave de la misma cuenta devuelve la misma respuesta, sin
 * repetir el efecto. Asi la bandeja de salida sin conexion del navegador puede reenviar sin miedo.
 */
final class Idempotencia {

    /** El trabajo de la escritura, dentro de la misma transaccion que la guarda. */
    @FunctionalInterface
    interface Escritura<T extends Message> {
        T hacer() throws SQLException;
    }

    private Idempotencia() {
    }

    static <T extends Message> T una(Connection c, Usuario usuario, UUID clave, String operacion, Parser<T> parser,
                                     Instant ahora, Escritura<T> escritura) throws SQLException {
        // Dos peticiones con la misma clave a la vez: la segunda espera aqui a que termine la primera.
        Bd.uno(c, "select pg_advisory_xact_lock(hashtextextended(?, 0)) is not null as listo",
                f -> true, usuario.cuentaId() + "/" + clave);
        record Previa(String operacion, byte[] respuesta) {
        }
        Optional<Previa> previa = Bd.uno(c, "select operacion, respuesta from idempotencia"
                + " where account_id = ? and clave = ?", f -> new Previa(f.getString("operacion"),
                f.getBytes("respuesta")), usuario.cuentaId(), clave);
        if (previa.isPresent()) {
            if (!previa.get().operacion().equals(operacion)) {
                throw new FalloRpc(Tipo.ARGUMENTO_INVALIDO, "Esa clave de idempotencia ya se uso en otra operacion");
            }
            try {
                return parser.parseFrom(previa.get().respuesta());
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException("Respuesta idempotente danada", e);
            }
        }
        T resultado = escritura.hacer();
        Bd.ejecutar(c, "insert into idempotencia (account_id, clave, operacion, respuesta, created_at)"
                + " values (?, ?, ?, ?, ?)", usuario.cuentaId(), clave, operacion, resultado.toByteArray(), ahora);
        return resultado;
    }
}

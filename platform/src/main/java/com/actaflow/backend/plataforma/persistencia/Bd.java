package com.actaflow.backend.plataforma.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Acceso a PostgreSQL con JDBC y SQL explicito, sin ORM: cada consulta se ve tal cual se ejecuta,
 * con parametros enlazados (nunca concatenados) y transacciones explicitas.
 */
public final class Bd {

    /** Trabajo dentro de una transaccion. */
    @FunctionalInterface
    public interface Trabajo<T> {
        T en(Connection conexion) throws SQLException;
    }

    /** Convierte una fila en un valor. */
    @FunctionalInterface
    public interface Fila<T> {
        T leer(ResultSet fila) throws SQLException;
    }

    private final DataSource origen;

    public Bd(DataSource origen) {
        this.origen = origen;
    }

    /** Ejecuta el trabajo en una transaccion: confirma si termina bien, deshace si lanza. */
    public <T> T transaccion(Trabajo<T> trabajo) {
        try (Connection conexion = origen.getConnection()) {
            conexion.setAutoCommit(false);
            try {
                T resultado = trabajo.en(conexion);
                conexion.commit();
                return resultado;
            } catch (SQLException | RuntimeException e) {
                conexion.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error de base de datos", e);
        }
    }

    public static <T> Optional<T> uno(Connection c, String sql, Fila<T> fila, Object... parametros)
            throws SQLException {
        List<T> filas = varios(c, sql, fila, parametros);
        return filas.isEmpty() ? Optional.empty() : Optional.of(filas.getFirst());
    }

    public static <T> List<T> varios(Connection c, String sql, Fila<T> fila, Object... parametros)
            throws SQLException {
        try (PreparedStatement sentencia = preparar(c, sql, parametros); ResultSet filas = sentencia.executeQuery()) {
            List<T> resultado = new ArrayList<>();
            while (filas.next()) {
                resultado.add(fila.leer(filas));
            }
            return resultado;
        }
    }

    public static int ejecutar(Connection c, String sql, Object... parametros) throws SQLException {
        try (PreparedStatement sentencia = preparar(c, sql, parametros)) {
            return sentencia.executeUpdate();
        }
    }

    public static Instant instante(ResultSet fila, String columna) throws SQLException {
        OffsetDateTime valor = fila.getObject(columna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }

    public static UUID uuid(ResultSet fila, String columna) throws SQLException {
        return fila.getObject(columna, UUID.class);
    }

    private static PreparedStatement preparar(Connection c, String sql, Object... parametros) throws SQLException {
        PreparedStatement sentencia = c.prepareStatement(sql);
        for (int i = 0; i < parametros.length; i++) {
            Object valor = parametros[i];
            if (valor instanceof Instant instante) {
                valor = instante.atOffset(ZoneOffset.UTC);
            }
            sentencia.setObject(i + 1, valor);
        }
        return sentencia;
    }
}

package com.actaflow.backend.identidad.soporte;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * PostgreSQL real para las pruebas sin Spring: un esquema propio ({@code identity_prueba}) con
 * las migraciones de produccion, recreado en cada prueba. La V1 solo comenta el esquema
 * {@code identity}, por eso se omite aqui.
 */
public final class BaseDatosPrueba implements DataSource {

    public static final String ESQUEMA = "identity_prueba";
    private static final List<String> MIGRACIONES = List.of("V2__cuentas_y_sesiones.sql");

    private final String url = entorno("ACTAFLOW_TEST_DB_URL", "jdbc:postgresql://localhost:5432/actaflow_test");
    private final String usuario = entorno("ACTAFLOW_TEST_DB_USERNAME", "actaflow_test");
    private final String clave = entorno("ACTAFLOW_TEST_DB_PASSWORD", "actaflow_test");

    private static String entorno(String nombre, String porDefecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? porDefecto : valor;
    }

    /** Esquema vacio con todas las migraciones aplicadas. */
    public BaseDatosPrueba recrear() {
        try (Connection c = DriverManager.getConnection(url, usuario, clave); Statement s = c.createStatement()) {
            s.execute("drop schema if exists " + ESQUEMA + " cascade");
            s.execute("create schema " + ESQUEMA);
            s.execute("set search_path to " + ESQUEMA);
            for (String migracion : MIGRACIONES) {
                try (InputStream sql = getClass().getResourceAsStream("/db/migration/" + migracion)) {
                    if (sql == null) {
                        throw new IllegalStateException("No se encontro la migracion " + migracion);
                    }
                    s.execute(new String(sql.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            return this;
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("No se pudo preparar la base de pruebas", e);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection c = DriverManager.getConnection(url, usuario, clave);
        c.setSchema(ESQUEMA);
        return c;
    }

    @Override
    public Connection getConnection(String u, String p) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter salida) {
    }

    @Override
    public void setLoginTimeout(int segundos) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> tipo) throws SQLException {
        throw new SQLException("No envuelve nada");
    }

    @Override
    public boolean isWrapperFor(Class<?> tipo) {
        return false;
    }
}

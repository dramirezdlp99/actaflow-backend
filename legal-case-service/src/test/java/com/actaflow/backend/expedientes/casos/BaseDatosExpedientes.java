package com.actaflow.backend.expedientes.casos;

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
 * Base de pruebas sin Spring: recrea el esquema legal_case_prueba y aplica las migraciones tal cual
 * (V2 solo limpia el esquema public y no hace falta aqui).
 */
final class BaseDatosExpedientes implements DataSource {

    static final String ESQUEMA = "legal_case_prueba";
    private static final List<String> MIGRACIONES = List.of("V1__cola_de_trabajos.sql",
            "V3__expedientes_y_participantes.sql", "V4__plantillas.sql", "V5__audiencias.sql",
            "V6__actas.sql");

    private final String url = entorno("ACTAFLOW_TEST_DB_URL", "jdbc:postgresql://localhost:5432/actaflow_test");
    private final String usuario = entorno("ACTAFLOW_TEST_DB_USERNAME", "actaflow_test");
    private final String clave = entorno("ACTAFLOW_TEST_DB_PASSWORD", "actaflow_test");

    private static String entorno(String nombre, String porDefecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? porDefecto : valor;
    }

    BaseDatosExpedientes recrear() {
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
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
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
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("No es un envoltorio");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }
}

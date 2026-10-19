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
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Base de pruebas sin Spring: recrea el esquema legal_case_prueba y aplica la migracion V3 tal cual
 * (V1 y V2 tocan el esquema legal_case por nombre y no hacen falta aqui).
 */
final class BaseDatosExpedientes implements DataSource {

    static final String ESQUEMA = "legal_case_prueba";

    private final String url = entorno("ACTAFLOW_TEST_DB_URL", "jdbc:postgresql://localhost:5432/actaflow_test");
    private final String usuario = entorno("ACTAFLOW_TEST_DB_USERNAME", "actaflow_test");
    private final String clave = entorno("ACTAFLOW_TEST_DB_PASSWORD", "actaflow_test");

    private static String entorno(String nombre, String porDefecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? porDefecto : valor;
    }

    BaseDatosExpedientes recrear() {
        try (Connection c = DriverManager.getConnection(url, usuario, clave); Statement s = c.createStatement();
             InputStream v3 = getClass().getResourceAsStream("/db/migration/V3__expedientes_y_participantes.sql")) {
            if (v3 == null) {
                throw new IllegalStateException("No se encontro la migracion V3 en el classpath");
            }
            s.execute("drop schema if exists " + ESQUEMA + " cascade");
            s.execute("create schema " + ESQUEMA);
            s.execute("set search_path to " + ESQUEMA);
            s.execute(new String(v3.readAllBytes(), StandardCharsets.UTF_8));
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

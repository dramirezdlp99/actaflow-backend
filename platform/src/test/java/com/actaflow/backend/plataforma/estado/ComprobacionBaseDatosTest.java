package com.actaflow.backend.plataforma.estado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.logging.Logger;

import javax.sql.DataSource;

import com.actaflow.contracts.system.v1.ComponentStatus;
import com.actaflow.contracts.system.v1.HealthState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contra PostgreSQL real: la misma base de pruebas que usan los servicios
 * (ACTAFLOW_TEST_DB_URL, por defecto actaflow_test en localhost).
 */
class ComprobacionBaseDatosTest {

    private static final String URL = entorno("ACTAFLOW_TEST_DB_URL", "jdbc:postgresql://localhost:5432/actaflow_test");
    private static final String USUARIO = entorno("ACTAFLOW_TEST_DB_USERNAME", "actaflow_test");
    private static final String CLAVE = entorno("ACTAFLOW_TEST_DB_PASSWORD", "actaflow_test");
    private static final String ESQUEMA = "prueba_plataforma";

    private static String entorno(String nombre, String porDefecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? porDefecto : valor;
    }

    /** DataSource minimo sobre DriverManager; los servicios usan Hikari. */
    private record Directo(String url, String usuario, String clave) implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, usuario, clave);
        }

        @Override
        public Connection getConnection(String u, String c) throws SQLException {
            return DriverManager.getConnection(url, u, c);
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

    private final DataSource base = new Directo(URL, USUARIO, CLAVE);

    private void sql(String... sentencias) throws SQLException {
        try (Connection c = base.getConnection(); Statement s = c.createStatement()) {
            for (String sentencia : sentencias) {
                s.execute(sentencia);
            }
        }
    }

    @BeforeEach
    void preparar() throws SQLException {
        sql("drop schema if exists " + ESQUEMA + " cascade", "create schema " + ESQUEMA,
                "create table " + ESQUEMA + ".flyway_schema_history (installed_rank int primary key,"
                        + " version varchar(50), success boolean not null)");
    }

    @AfterEach
    void limpiar() throws SQLException {
        sql("drop schema if exists " + ESQUEMA + " cascade");
    }

    private ComponentStatus comprobar(DataSource ds) {
        return new ComprobacionBaseDatos(ds, ESQUEMA, Duration.ofSeconds(5)).comprobar();
    }

    @Test
    void informaLaUltimaMigracionExitosaDelEsquemaDelServicio() throws SQLException {
        // Fila 0: la marca "<< Flyway Schema Creation >>" que Flyway escribe sin version.
        sql("insert into " + ESQUEMA + ".flyway_schema_history values (0, null, true), (1, '1', true),"
                + " (2, '2', true), (3, '3', false)");
        ComponentStatus estado = comprobar(base);
        assertEquals(HealthState.HEALTH_STATE_OK, estado.getState());
        assertEquals("2", estado.getVersion());
        assertEquals("PostgreSQL responde; esquema prueba_plataforma v2", estado.getDetail());
    }

    @Test
    void conSoloLaMarcaDeCreacionDelEsquemaEstaDegradada() throws SQLException {
        sql("insert into " + ESQUEMA + ".flyway_schema_history values (0, null, true)");
        assertEquals(HealthState.HEALTH_STATE_DEGRADED, comprobar(base).getState());
    }

    @Test
    void sinConexionEstaDownSinRevelarElError() {
        ComponentStatus estado = comprobar(new Directo("jdbc:postgresql://127.0.0.1:9/nada", "x", "y"));
        assertEquals(HealthState.HEALTH_STATE_DOWN, estado.getState());
        assertEquals("La base de datos no responde", estado.getDetail());
    }

    @Test
    void rechazaNombresDeEsquemaPeligrosos() {
        for (String malo : new String[] {"a\"; drop table x; --", "Mayusculas", "", "1abc"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ComprobacionBaseDatos(base, malo, Duration.ofSeconds(1)), malo);
        }
    }
}

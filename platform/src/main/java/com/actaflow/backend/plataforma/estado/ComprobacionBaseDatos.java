package com.actaflow.backend.plataforma.estado;

import java.lang.System.Logger.Level;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.actaflow.contracts.system.v1.ComponentKind;
import com.actaflow.contracts.system.v1.ComponentStatus;
import com.actaflow.contracts.system.v1.HealthState;

/**
 * Comprueba PostgreSQL e informa la ultima migracion de Flyway aplicada en el esquema del servicio.
 *
 * <p>Cada microservicio tiene su propio esquema (y su propia tabla de historial de Flyway) dentro de
 * la misma base de Neon: identidad no ve las tablas de expedientes ni al reves.
 */
public final class ComprobacionBaseDatos implements Comprobacion {

    private static final System.Logger LOG = System.getLogger(ComprobacionBaseDatos.class.getName());
    private static final Pattern ESQUEMA_VALIDO = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final int TIMEOUT_CONSULTA_SEGUNDOS = 5;

    private final DataSource baseDatos;
    private final String esquema;
    private final Duration limite;

    public ComprobacionBaseDatos(DataSource baseDatos, String esquema, Duration limite) {
        if (!ESQUEMA_VALIDO.matcher(esquema).matches()) {
            throw new IllegalArgumentException("Nombre de esquema invalido: " + esquema);
        }
        this.baseDatos = baseDatos;
        this.esquema = esquema;
        this.limite = limite;
    }

    @Override
    public String id() {
        return "database";
    }

    @Override
    public ComponentKind tipo() {
        return ComponentKind.COMPONENT_KIND_DATABASE;
    }

    @Override
    public Duration limite() {
        return limite;
    }

    @Override
    public ComponentStatus comprobar() {
        // El esquema ya se valido contra una lista blanca de caracteres: no hay inyeccion posible.
        String sql = "select version from \"" + esquema + "\".flyway_schema_history"
                + " where success and version is not null order by installed_rank desc limit 1";
        try (Connection conexion = baseDatos.getConnection();
             PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setQueryTimeout(TIMEOUT_CONSULTA_SEGUNDOS);
            try (ResultSet filas = consulta.executeQuery()) {
                if (!filas.next()) {
                    return ComponentStatus.newBuilder().setState(HealthState.HEALTH_STATE_DEGRADED)
                            .setDetail("Sin migraciones aplicadas en " + esquema).build();
                }
                String version = filas.getString(1);
                return ComponentStatus.newBuilder().setState(HealthState.HEALTH_STATE_OK)
                        .setDetail("PostgreSQL responde; esquema " + esquema + " v" + version)
                        .setVersion(version).build();
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "La base de datos no responde: {0}", e.getMessage());
            return ComponentStatus.newBuilder().setState(HealthState.HEALTH_STATE_DOWN)
                    .setDetail("La base de datos no responde").build();
        }
    }
}

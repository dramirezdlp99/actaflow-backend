package com.actaflow.backend.estado.infrastructure;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.actaflow.backend.estado.application.VerificadorBaseDatos;
import com.actaflow.backend.estado.domain.VerificacionComponente;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifica PostgreSQL y reporta la ultima migracion de Flyway aplicada. */
public class JdbcVerificadorBaseDatos implements VerificadorBaseDatos {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcVerificadorBaseDatos.class);
    private static final int TIMEOUT_CONSULTA_SEGUNDOS = 5;
    private static final String SQL_ULTIMA_MIGRACION =
            "select version from flyway_schema_history where success order by installed_rank desc limit 1";

    private final JdbcTemplate jdbc;

    public JdbcVerificadorBaseDatos(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(TIMEOUT_CONSULTA_SEGUNDOS);
    }

    @Override
    public VerificacionComponente verificar() {
        long inicio = System.nanoTime();
        try {
            List<String> versiones = jdbc.queryForList(SQL_ULTIMA_MIGRACION, String.class);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
            if (versiones.isEmpty()) {
                return VerificacionComponente.degradado(ms, "Sin migraciones aplicadas", null);
            }
            String version = versiones.getFirst();
            return VerificacionComponente.ok(ms, "PostgreSQL responde; esquema v" + version, version);
        } catch (DataAccessException e) {
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
            LOG.warn("La base de datos no responde: {}", e.getMostSpecificCause().getMessage());
            return VerificacionComponente.caido(ms, "La base de datos no responde");
        }
    }
}

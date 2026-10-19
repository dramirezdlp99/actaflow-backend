package com.actaflow.backend.identidad.aplicacion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.actaflow.backend.identidad.dominio.Criptografia;
import com.actaflow.backend.identidad.dominio.Rol;
import com.actaflow.backend.plataforma.persistencia.Bd;

/**
 * Primer arranque: crea la organizacion y la invitacion del primer administrador.
 *
 * <p>Nadie puede registrarse sin invitacion, asi que alguien tiene que ser el primero. El token
 * lo elige quien despliega (ACTAFLOW_BOOTSTRAP_INVITATION_TOKEN) y nunca aparece en los logs; la
 * base solo guarda su hash. Si ya existe alguna organizacion, no hace nada.
 */
public final class Arranque {

    static final Duration VIGENCIA = Duration.ofDays(30);

    private final Bd bd;
    private final Criptografia cripto;
    private final Clock reloj;

    public Arranque(Bd bd, Criptografia cripto, Clock reloj) {
        this.bd = bd;
        this.cripto = cripto;
        this.reloj = reloj;
    }

    /** @return true si creo la organizacion y la invitacion del administrador */
    public boolean ejecutar(String organizacion, String correoAdmin, String token) {
        if (vacio(organizacion) || vacio(correoAdmin) || vacio(token)) {
            return false;
        }
        if (token.length() < 32) {
            throw new IllegalStateException("ACTAFLOW_BOOTSTRAP_INVITATION_TOKEN debe tener al menos 32 caracteres");
        }
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> {
            // Bloqueo de toda la tabla: si arrancan dos instancias a la vez, solo una crea la organizacion.
            Bd.ejecutar(c, "lock table organization in exclusive mode");
            if (Bd.uno(c, "select 1 as uno from organization limit 1", f -> 1).isPresent()) {
                return false;
            }
            UUID id = UUID.randomUUID();
            Bd.ejecutar(c, "insert into organization (id, name, created_at) values (?, ?, ?)", id,
                    organizacion.strip(), ahora);
            Bd.ejecutar(c, "insert into invitation (id, organization_id, email, role, token_hash, expires_at,"
                    + " created_at) values (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), id,
                    ServicioAutenticacion.normalizarCorreo(correoAdmin), Rol.ADMIN.name(),
                    cripto.huella("invitation", token), ahora.plus(VIGENCIA), ahora);
            return true;
        });
    }

    private static boolean vacio(String valor) {
        return valor == null || valor.isBlank();
    }
}

package com.actaflow.backend.expedientes.seguridad;

import java.util.UUID;

/**
 * Quien hace la llamada, segun su token de acceso (firmado por identity-service).
 *
 * @param cuentaId       cuenta (sub)
 * @param organizacionId organizacion (org)
 * @param rol            rol de plataforma: ADMIN, MEMBER o EXTERNAL
 * @param correo         correo de la cuenta; con el se vinculan las invitaciones a casos
 * @param nombre         nombre visible
 */
public record Usuario(UUID cuentaId, UUID organizacionId, String rol, String correo, String nombre) {

    /** Las cuentas externas (partes y apoderados) no crean expedientes. */
    public boolean esExterno() {
        return "EXTERNAL".equals(rol);
    }
}

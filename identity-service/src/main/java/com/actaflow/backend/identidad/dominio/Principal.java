package com.actaflow.backend.identidad.dominio;

import java.util.UUID;

/** Quien hace la llamada, segun su token de acceso ya verificado. */
public record Principal(UUID cuentaId, UUID organizacionId, Rol rol) {

    public boolean esAdmin() {
        return rol == Rol.ADMIN;
    }
}

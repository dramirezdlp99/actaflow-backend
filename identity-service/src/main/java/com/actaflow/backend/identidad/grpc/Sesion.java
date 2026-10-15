package com.actaflow.backend.identidad.grpc;

import com.actaflow.backend.identidad.dominio.FalloIdentidad;
import com.actaflow.backend.identidad.dominio.FalloIdentidad.Tipo;
import com.actaflow.backend.identidad.dominio.Principal;

import io.grpc.Context;

/** Principal de la llamada en curso, si traia un token de acceso valido. */
final class Sesion {

    static final Context.Key<Principal> CLAVE = Context.key("actaflow-principal");

    private Sesion() {
    }

    /** Principal autenticado o UNAUTHENTICATED. */
    static Principal exigir() {
        Principal principal = CLAVE.get();
        if (principal == null) {
            throw new FalloIdentidad(Tipo.NO_AUTENTICADO, "Inicia sesion para continuar");
        }
        return principal;
    }
}

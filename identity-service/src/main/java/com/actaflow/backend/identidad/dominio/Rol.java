package com.actaflow.backend.identidad.dominio;

import com.actaflow.contracts.identity.v1.PlatformRole;

/** Rol de una cuenta dentro de su organizacion. Se guarda por nombre en la base de datos. */
public enum Rol {
    /** Administra la organizacion: invita, cambia roles y desactiva miembros. */
    ADMIN(PlatformRole.PLATFORM_ROLE_ADMIN),
    /** Conciliador o asistente del centro. */
    MEMBER(PlatformRole.PLATFORM_ROLE_MEMBER),
    /** Parte o apoderado externo: solo ve los casos a los que lo invitan. */
    EXTERNAL(PlatformRole.PLATFORM_ROLE_EXTERNAL);

    private final PlatformRole contrato;

    Rol(PlatformRole contrato) {
        this.contrato = contrato;
    }

    public PlatformRole contrato() {
        return contrato;
    }

    /** Rol del contrato a rol del dominio; UNSPECIFIED o desconocido no es un rol valido. */
    public static Rol desde(PlatformRole rol) {
        for (Rol candidato : values()) {
            if (candidato.contrato == rol) {
                return candidato;
            }
        }
        throw new IllegalArgumentException("Rol sin especificar");
    }
}

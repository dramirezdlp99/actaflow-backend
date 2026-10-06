package com.actaflow.backend.compartido.seguridad;

/** Rechazo de una firma HMAC. El motivo solo se registra en el log, nunca se envia al cliente. */
public class FirmaInvalidaException extends RuntimeException {

    /** Por que se rechazo la firma. */
    public enum Motivo {
        FALTA_CABECERA,
        TIMESTAMP_INVALIDO,
        FUERA_DE_VENTANA,
        FIRMA_INCORRECTA
    }

    private final Motivo motivo;

    public FirmaInvalidaException(Motivo motivo) {
        super(motivo.name());
        this.motivo = motivo;
    }

    public Motivo motivo() {
        return motivo;
    }
}

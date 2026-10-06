package com.actaflow.backend.estado.domain;

/** Estado de una capa del sistema vista desde el backend. */
public enum EstadoComponente {
    /** Responde y esta listo para trabajar. */
    OK,
    /** Responde, pero le falta algo (por ejemplo, el modelo de IA configurado). */
    DEGRADADO,
    /** No responde o responde con error. */
    CAIDO
}

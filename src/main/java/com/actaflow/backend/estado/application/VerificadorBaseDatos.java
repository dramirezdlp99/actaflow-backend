package com.actaflow.backend.estado.application;

import com.actaflow.backend.estado.domain.VerificacionComponente;

/** Puerto de salida: comprueba que la base de datos responde. */
public interface VerificadorBaseDatos {

    VerificacionComponente verificar();
}

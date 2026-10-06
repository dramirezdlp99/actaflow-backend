package com.actaflow.backend.estado.application;

import com.actaflow.backend.estado.domain.VerificacionComponente;

/** Puerto de salida: comprueba que el motor de IA responde (y lo despierta si dormia). */
public interface VerificadorMotorIa {

    VerificacionComponente verificar();
}

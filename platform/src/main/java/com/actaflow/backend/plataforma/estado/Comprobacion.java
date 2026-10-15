package com.actaflow.backend.plataforma.estado;

import java.time.Duration;

import com.actaflow.contracts.system.v1.ComponentKind;
import com.actaflow.contracts.system.v1.ComponentStatus;

/**
 * Una dependencia del servicio que se puede comprobar: su base de datos, otro microservicio...
 *
 * <p>{@link #comprobar()} puede bloquear (corre en su propio hilo virtual) y puede lanzar
 * excepciones: {@link VerificadorEstado} las convierte en un componente {@code DOWN}.
 */
public interface Comprobacion {

    /** Identificador estable del componente, por ejemplo {@code database} o {@code ai-engine}. */
    String id();

    ComponentKind tipo();

    /** Tiempo maximo, medido desde que empieza la consulta de estado. */
    Duration limite();

    /** Estado, detalle y version del componente; el resto lo completa {@link VerificadorEstado}. */
    ComponentStatus comprobar();
}

package com.actaflow.backend.estado.infrastructure;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Respuesta de {@code GET /health} del motor de IA (solo los campos que usa el backend). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SaludMotor(String servicio, String version, String estado, Modelos modelos) {

    /** Modelos instalados en el motor. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Modelos(Asr asr, Nlp nlp) {
    }

    /** Voz a texto: modelo configurado, modelos en disco y si el configurado esta listo. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Asr(String configurado, List<String> disponibles, boolean listo) {
    }

    /** Modelos de spaCy instalados. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Nlp(List<String> disponibles) {
    }
}

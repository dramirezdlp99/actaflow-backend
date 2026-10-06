package com.actaflow.backend.compartido.web;

import static com.actaflow.backend.soporte.DatosPrueba.relojFijo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ApiErrorTest {

    @Test
    void construyeElJsonConElFormatoUniforme() {
        ApiError error = ApiError.de(401, "firma_invalida", "Peticion no autorizada", "/api/v1/status",
                relojFijo(1_760_000_000L));
        assertEquals("{\"estado\":401,\"codigo\":\"firma_invalida\",\"mensaje\":\"Peticion no autorizada\","
                + "\"ruta\":\"/api/v1/status\",\"instante\":\"2025-10-09T08:53:20Z\"}", error.comoJson());
    }

    @Test
    void escapaComillasBarrasYCaracteresDeControl() {
        assertEquals("a\\\"b\\\\c\\nd\\u0001", ApiError.escapar("a\"b\\c\nd\u0001"));
        assertEquals("", ApiError.escapar(null));
    }
}

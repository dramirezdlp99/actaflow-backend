package com.actaflow.backend.identidad.dominio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class FirmaTokensTest {

    private static final String SECRETO = "token-secret-de-prueba-publico-solo-para-tests-01";
    private static final Instant AHORA = Instant.ofEpochSecond(1_760_000_000L);
    private final FirmaTokens firma = new FirmaTokens(SECRETO, Duration.ofMinutes(15));
    private final Principal principal = new Principal(UUID.randomUUID(), UUID.randomUUID(), Rol.MEMBER);

    @Test
    void unTokenEmitidoSeVerificaHastaQueVence() {
        FirmaTokens.TokenEmitido emitido = firma.emitir(principal, AHORA);
        assertEquals(AHORA.plusSeconds(900), emitido.venceEn());
        assertEquals(principal, firma.verificar(emitido.token(), AHORA.plusSeconds(899)).orElseThrow());
        assertTrue(firma.verificar(emitido.token(), AHORA.plusSeconds(900)).isEmpty());
    }

    @Test
    void laClaveEsDeterministaYOtroSecretoNoVerifica() {
        FirmaTokens misma = new FirmaTokens(SECRETO, Duration.ofMinutes(15));
        FirmaTokens otra = new FirmaTokens("otro-secreto-distinto-de-al-menos-32-caracteres", Duration.ofMinutes(15));
        assertArrayEquals(firma.clavePublica().getEncoded(), misma.clavePublica().getEncoded());
        assertEquals(firma.idClave(), misma.idClave());
        assertNotEquals(firma.idClave(), otra.idClave());
        String token = firma.emitir(principal, AHORA).token();
        assertEquals(principal, misma.verificar(token, AHORA).orElseThrow());
        assertTrue(otra.verificar(token, AHORA).isEmpty());
    }

    @Test
    void cualquierAlteracionInvalidaElToken() {
        String token = firma.emitir(principal, AHORA).token();
        String[] partes = token.split("\\.");
        String cargaAdmin = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8)
                        .replace("MEMBER", "ADMIN").getBytes(StandardCharsets.UTF_8));
        assertTrue(firma.verificar(partes[0] + "." + cargaAdmin + "." + partes[2], AHORA).isEmpty());
        assertTrue(firma.verificar(token + "x", AHORA).isEmpty());
        assertTrue(firma.verificar("no.es.token", AHORA).isEmpty());
        assertTrue(firma.verificar(null, AHORA).isEmpty());
    }

    @Test
    void otrosServiciosVerificanConLaClavePublica() {
        String token = firma.emitir(principal, AHORA).token();
        assertEquals(principal,
                FirmaTokens.verificar(token, AHORA, firma.clavePublica(), firma.idClave()).orElseThrow());
    }

    @Test
    void exigeUnSecretoLargo() {
        assertThrows(IllegalArgumentException.class, () -> new FirmaTokens("corto", Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new Criptografia("corto"));
    }
}

package com.actaflow.backend.plataforma.seguridad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TokenAccesoTest {

    private static final Instant AHORA = Instant.ofEpochSecond(1_760_000_000L);
    private final KeyPair claves = par();
    private final String kid = TokenAcceso.idDeClave(claves.getPublic());
    private final UUID cuenta = UUID.randomUUID();
    private final UUID organizacion = UUID.randomUUID();

    private static KeyPair par() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String token(String nombre, long vence) {
        Map<String, Object> carga = new LinkedHashMap<>();
        carga.put("iss", TokenAcceso.EMISOR);
        carga.put("sub", cuenta.toString());
        carga.put("org", organizacion.toString());
        carga.put("role", "MEMBER");
        carga.put("email", "ana@prueba.test");
        carga.put("name", nombre);
        carga.put("exp", vence);
        String firmable = TokenAcceso.firmable(kid, carga);
        try {
            Signature firma = Signature.getInstance("Ed25519");
            firma.initSign(claves.getPrivate());
            firma.update(firmable.getBytes(StandardCharsets.US_ASCII));
            return firmable + "." + TokenAcceso.firma(firma.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void verificaYDevuelveTodosLosReclamos() {
        var r = TokenAcceso.verificar(token("Ana Maria", AHORA.getEpochSecond() + 60), AHORA, claves.getPublic(),
                kid).orElseThrow();
        assertEquals(cuenta, r.cuenta());
        assertEquals(organizacion, r.organizacion());
        assertEquals("MEMBER", r.rol());
        assertEquals("ana@prueba.test", r.correo());
        assertEquals("Ana Maria", r.nombre());
    }

    @Test
    void unNombreConComillasNoPuedeInyectarOtroRol() {
        String malicioso = "x\",\"role\":\"ADMIN";
        var r = TokenAcceso.verificar(token(malicioso, AHORA.getEpochSecond() + 60), AHORA, claves.getPublic(), kid)
                .orElseThrow();
        assertEquals("MEMBER", r.rol());
        assertEquals(malicioso, r.nombre());
    }

    @Test
    void conservaTildesSaltosYBarras() {
        String nombre = "Ñoño \\ Pérez\n\t\u0001";
        var r = TokenAcceso.verificar(token(nombre, AHORA.getEpochSecond() + 60), AHORA, claves.getPublic(), kid)
                .orElseThrow();
        assertEquals(nombre, r.nombre());
    }

    @Test
    void rechazaVencidoOtraClaveYBasura() {
        String vencido = token("Ana", AHORA.getEpochSecond());
        assertTrue(TokenAcceso.verificar(vencido, AHORA, claves.getPublic(), kid).isEmpty());
        String valido = token("Ana", AHORA.getEpochSecond() + 60);
        KeyPair otra = par();
        assertTrue(TokenAcceso.verificar(valido, AHORA, otra.getPublic(), kid).isEmpty());
        assertTrue(TokenAcceso.verificar(valido, AHORA, claves.getPublic(), "otro-kid").isEmpty());
        assertTrue(TokenAcceso.verificar("a.b.c", AHORA, claves.getPublic(), kid).isEmpty());
        assertTrue(TokenAcceso.verificar(null, AHORA, claves.getPublic(), kid).isEmpty());
    }

    @Test
    void laClavePublicaViajaComoSpki() {
        var reconstruida = TokenAcceso.clavePublica(claves.getPublic().getEncoded());
        assertEquals(kid, TokenAcceso.idDeClave(reconstruida));
    }
}

package com.actaflow.backend.expedientes.casos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.actaflow.backend.expedientes.seguridad.ClavesIdentidad;
import com.actaflow.backend.plataforma.seguridad.TokenAcceso;
import com.actaflow.contracts.identity.v1.VerificationKey;
import com.google.protobuf.ByteString;

import org.junit.jupiter.api.Test;

class ClavesIdentidadTest {

    private Instant ahora = Instant.parse("2026-10-19T12:00:00Z");
    private final Clock reloj = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zona) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    };

    private static VerificationKey clave(KeyPair par) {
        return VerificationKey.newBuilder().setKeyId(TokenAcceso.idDeClave(par.getPublic())).setAlgorithm("Ed25519")
                .setPublicKeySpki(ByteString.copyFrom(par.getPublic().getEncoded())).build();
    }

    private static KeyPair par() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void pideUnaVezLaUsaDeCacheYAnteUnaClaveNuevaVuelveAPedirConFreno() {
        KeyPair vieja = par();
        KeyPair nueva = par();
        List<VerificationKey> publicadas = new ArrayList<>(List.of(clave(vieja)));
        AtomicInteger llamadas = new AtomicInteger();
        ClavesIdentidad claves = new ClavesIdentidad(() -> {
            llamadas.incrementAndGet();
            return List.copyOf(publicadas);
        }, reloj);
        String idVieja = TokenAcceso.idDeClave(vieja.getPublic());
        String idNueva = TokenAcceso.idDeClave(nueva.getPublic());

        assertTrue(claves.clave(idVieja).isPresent());
        assertTrue(claves.clave(idVieja).isPresent());
        assertEquals(1, llamadas.get(), "la segunda vez sale de la cache");

        // Tokens con un id desconocido no disparan una llamada por peticion.
        assertTrue(claves.clave("inventado").isEmpty());
        assertTrue(claves.clave("inventado-2").isEmpty());
        assertEquals(1, llamadas.get());

        // identity roto su secreto: pasados 30 s, un id nuevo hace pedir otra vez.
        publicadas.add(clave(nueva));
        ahora = ahora.plus(Duration.ofSeconds(31));
        assertTrue(claves.clave(idNueva).isPresent());
        assertEquals(2, llamadas.get());
    }

    @Test
    void siIdentityNoRespondeConservaLasClavesConocidas() {
        KeyPair par = par();
        AtomicInteger llamadas = new AtomicInteger();
        ClavesIdentidad claves = new ClavesIdentidad(() -> {
            if (llamadas.incrementAndGet() > 1) {
                throw new IllegalStateException("identity dormido");
            }
            return List.of(clave(par));
        }, reloj);
        String id = TokenAcceso.idDeClave(par.getPublic());
        assertTrue(claves.clave(id).isPresent());
        ahora = ahora.plus(Duration.ofHours(2));
        assertTrue(claves.clave(id).isPresent(), "vencidas pero utiles mientras identity despierta");
        assertEquals(2, llamadas.get());
    }
}

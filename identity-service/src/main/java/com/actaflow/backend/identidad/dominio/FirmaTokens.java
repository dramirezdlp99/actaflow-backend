package com.actaflow.backend.identidad.dominio;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.actaflow.backend.plataforma.seguridad.TokenAcceso;

/**
 * Tokens de acceso: JWT compactos firmados con EdDSA (Ed25519), solo con el JDK. El formato y la
 * verificacion viven en {@link TokenAcceso} (platform), compartidos con los demas servicios.
 *
 * <p>El par de claves se deriva de forma determinista de ACTAFLOW_IDENTITY_TOKEN_SECRET: todas
 * las instancias del servicio firman con la misma clave y no hay archivos PEM que guardar. La
 * clave publica se publica por TokenVerificationService para que los otros servicios verifiquen
 * los tokens localmente, sin llamar a identidad en cada peticion.
 */
public final class FirmaTokens {

    public static final String ALGORITMO = TokenAcceso.ALGORITMO;
    public static final String EMISOR = TokenAcceso.EMISOR;

    private final KeyPair claves;
    private final String idClave;
    private final Duration vigencia;

    public FirmaTokens(String secreto, Duration vigencia) {
        if (secreto == null || secreto.length() < 32) {
            throw new IllegalArgumentException("ACTAFLOW_IDENTITY_TOKEN_SECRET debe tener al menos 32 caracteres");
        }
        this.claves = parDeterminista(new Criptografia(secreto).huella("token-signing-key", "ed25519"));
        this.idClave = TokenAcceso.idDeClave(claves.getPublic());
        this.vigencia = vigencia;
    }

    /** Token emitido y su vencimiento. */
    public record TokenEmitido(String token, Instant venceEn) {
    }

    /**
     * Firma un token de acceso. El correo y el nombre viajan en el token para que los otros
     * servicios (por ejemplo, expedientes) sepan a quien atienden sin llamar a identidad.
     */
    public TokenEmitido emitir(Principal principal, String correo, String nombre, Instant ahora) {
        Instant vence = ahora.plus(vigencia);
        Map<String, Object> carga = new LinkedHashMap<>();
        carga.put("iss", EMISOR);
        carga.put("sub", principal.cuentaId().toString());
        carga.put("org", principal.organizacionId().toString());
        carga.put("role", principal.rol().name());
        carga.put("email", correo);
        carga.put("name", nombre);
        carga.put("iat", ahora.getEpochSecond());
        carga.put("exp", vence.getEpochSecond());
        carga.put("jti", UUID.randomUUID().toString());
        String firmable = TokenAcceso.firmable(idClave, carga);
        return new TokenEmitido(firmable + "." + TokenAcceso.firma(firmar(firmable)), vence);
    }

    /** Principal del token si la firma, el emisor y el vencimiento son validos. */
    public Optional<Principal> verificar(String token, Instant ahora) {
        return verificar(token, ahora, claves.getPublic(), idClave);
    }

    /** Verificacion con una clave publica dada (la usan otros servicios con GetVerificationKeys). */
    public static Optional<Principal> verificar(String token, Instant ahora, PublicKey clave, String idClave) {
        return TokenAcceso.verificar(token, ahora, clave, idClave).flatMap(r -> {
            try {
                return Optional.of(new Principal(r.cuenta(), r.organizacion(), Rol.valueOf(r.rol())));
            } catch (IllegalArgumentException | NullPointerException e) {
                return Optional.empty();
            }
        });
    }

    public PublicKey clavePublica() {
        return claves.getPublic();
    }

    public String idClave() {
        return idClave;
    }

    private byte[] firmar(String firmable) {
        try {
            Signature firma = Signature.getInstance("Ed25519");
            firma.initSign(claves.getPrivate());
            firma.update(firmable.getBytes(StandardCharsets.US_ASCII));
            return firma.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo firmar el token", e);
        }
    }

    /** Ed25519 toma su clave privada de 32 bytes aleatorios: aqui esos bytes salen de una semilla fija. */
    private static KeyPair parDeterminista(byte[] semilla) {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("Ed25519");
            generador.initialize(NamedParameterSpec.ED25519, new SecureRandom() {
                private static final long serialVersionUID = 1L;

                @Override
                public void nextBytes(byte[] destino) {
                    System.arraycopy(semilla, 0, destino, 0, Math.min(semilla.length, destino.length));
                }
            });
            return generador.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 no disponible", e);
        }
    }
}

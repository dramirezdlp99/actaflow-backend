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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokens de acceso: JWT compactos firmados con EdDSA (Ed25519), solo con el JDK.
 *
 * <p>El par de claves se deriva de forma determinista de ACTAFLOW_IDENTITY_TOKEN_SECRET: todas
 * las instancias del servicio firman con la misma clave y no hay archivos PEM que guardar. La
 * clave publica se publica por TokenVerificationService para que los otros servicios verifiquen
 * los tokens localmente, sin llamar a identidad en cada peticion.
 */
public final class FirmaTokens {

    public static final String ALGORITMO = "EdDSA";
    public static final String EMISOR = "actaflow-identity";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder D64 = Base64.getUrlDecoder();
    private static final Pattern CAMPO = Pattern.compile("\"([a-z]+)\":(?:\"([^\"\\\\]*)\"|(-?\\d+))");

    private final KeyPair claves;
    private final String idClave;
    private final Duration vigencia;

    public FirmaTokens(String secreto, Duration vigencia) {
        if (secreto == null || secreto.length() < 32) {
            throw new IllegalArgumentException("ACTAFLOW_IDENTITY_TOKEN_SECRET debe tener al menos 32 caracteres");
        }
        this.claves = parDeterminista(new Criptografia(secreto).huella("token-signing-key", "ed25519"));
        this.idClave = Criptografia.sha256Hex(claves.getPublic().getEncoded()).substring(0, 16);
        this.vigencia = vigencia;
    }

    /** Token emitido y su vencimiento. */
    public record TokenEmitido(String token, Instant venceEn) {
    }

    public TokenEmitido emitir(Principal principal, Instant ahora) {
        Instant vence = ahora.plus(vigencia);
        Map<String, Object> carga = new LinkedHashMap<>();
        carga.put("iss", EMISOR);
        carga.put("sub", principal.cuentaId().toString());
        carga.put("org", principal.organizacionId().toString());
        carga.put("role", principal.rol().name());
        carga.put("iat", ahora.getEpochSecond());
        carga.put("exp", vence.getEpochSecond());
        carga.put("jti", UUID.randomUUID().toString());
        String cabecera = "{\"alg\":\"" + ALGORITMO + "\",\"typ\":\"JWT\",\"kid\":\"" + idClave + "\"}";
        String firmable = b64(cabecera) + "." + b64(json(carga));
        return new TokenEmitido(firmable + "." + B64.encodeToString(firmar(firmable)), vence);
    }

    /** Principal del token si la firma, el emisor y el vencimiento son validos. */
    public Optional<Principal> verificar(String token, Instant ahora) {
        return verificar(token, ahora, claves.getPublic(), idClave);
    }

    /** Verificacion con una clave publica dada (la usan otros servicios con GetVerificationKeys). */
    public static Optional<Principal> verificar(String token, Instant ahora, PublicKey clave, String idClave) {
        try {
            String[] partes = token == null ? new String[0] : token.split("\\.", -1);
            if (partes.length != 3) {
                return Optional.empty();
            }
            Map<String, String> cabecera = leer(new String(D64.decode(partes[0]), StandardCharsets.UTF_8));
            if (!ALGORITMO.equals(cabecera.get("alg")) || !idClave.equals(cabecera.get("kid"))) {
                return Optional.empty();
            }
            Signature verificador = Signature.getInstance("Ed25519");
            verificador.initVerify(clave);
            verificador.update((partes[0] + "." + partes[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verificador.verify(D64.decode(partes[2]))) {
                return Optional.empty();
            }
            Map<String, String> carga = leer(new String(D64.decode(partes[1]), StandardCharsets.UTF_8));
            long vence = Long.parseLong(carga.getOrDefault("exp", "0"));
            if (!EMISOR.equals(carga.get("iss")) || ahora.getEpochSecond() >= vence) {
                return Optional.empty();
            }
            return Optional.of(new Principal(UUID.fromString(carga.get("sub")), UUID.fromString(carga.get("org")),
                    Rol.valueOf(carga.get("role"))));
        } catch (IllegalArgumentException | NullPointerException | GeneralSecurityException e) {
            return Optional.empty();
        }
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

    private static String b64(String texto) {
        return B64.encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }

    /** JSON plano de cadenas y numeros; los valores son UUID, nombres de enum y epoch: sin escapes. */
    private static String json(Map<String, Object> campos) {
        StringBuilder salida = new StringBuilder("{");
        for (Map.Entry<String, Object> campo : campos.entrySet()) {
            if (salida.length() > 1) {
                salida.append(',');
            }
            salida.append('"').append(campo.getKey()).append("\":");
            Object valor = campo.getValue();
            salida.append(valor instanceof Number ? valor.toString() : "\"" + valor + "\"");
        }
        return salida.append('}').toString();
    }

    private static Map<String, String> leer(String json) {
        Map<String, String> campos = new LinkedHashMap<>();
        Matcher m = CAMPO.matcher(json);
        while (m.find()) {
            campos.put(m.group(1), m.group(2) != null ? m.group(2) : m.group(3));
        }
        return campos;
    }
}

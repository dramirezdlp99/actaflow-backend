package com.actaflow.backend.plataforma.seguridad;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formato de los tokens de acceso de ActaFlow: JWT compactos firmados con EdDSA (Ed25519).
 *
 * <p>identity-service los firma; cualquier otro servicio los verifica aqui, localmente, con la clave
 * publica que identity publica en TokenVerificationService. Solo usa el JDK.
 */
public final class TokenAcceso {

    public static final String ALGORITMO = "EdDSA";
    public static final String EMISOR = "actaflow-identity";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder D64 = Base64.getUrlDecoder();
    /** "clave": "texto con escapes" o "clave": numero. */
    private static final Pattern CAMPO =
            Pattern.compile("\"([a-z]+)\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(-?\\d+))");

    private TokenAcceso() {
    }

    /**
     * Lo que dice un token valido.
     *
     * @param cuenta       cuenta autenticada (sub)
     * @param organizacion organizacion de la cuenta (org)
     * @param rol          rol de plataforma: ADMIN, MEMBER o EXTERNAL (role)
     * @param correo       correo de la cuenta (email); vacio en tokens antiguos
     * @param nombre       nombre visible (name); vacio en tokens antiguos
     * @param vence        vencimiento (exp)
     */
    public record Reclamos(UUID cuenta, UUID organizacion, String rol, String correo, String nombre, Instant vence) {
    }

    /** Cabecera y carga firmables; quien firma agrega {@code "." + firma}. */
    public static String firmable(String idClave, Map<String, Object> carga) {
        Map<String, Object> cabecera = new LinkedHashMap<>();
        cabecera.put("alg", ALGORITMO);
        cabecera.put("typ", "JWT");
        cabecera.put("kid", idClave);
        return b64(json(cabecera)) + "." + b64(json(carga));
    }

    public static String firma(byte[] bytes) {
        return B64.encodeToString(bytes);
    }

    /** Reclamo que solo llevan las pruebas de presencia: un token con el nunca es de acceso. */
    public static final String RECLAMO_PROPOSITO = "pur";

    /**
     * Prueba de presencia (AuthService.ConfirmPresence): la persona confirmo un codigo TOTP para un
     * proposito concreto, por ejemplo aceptar un acta.
     *
     * @param cuenta    cuenta que la confirmo (sub)
     * @param proposito para que sirve (pur)
     * @param vence     vencimiento (exp)
     */
    public record Presencia(UUID cuenta, String proposito, Instant vence) {
    }

    /**
     * Reclamos del token si la firma, el id de clave, el emisor y el vencimiento son validos. Una
     * prueba de presencia (lleva {@code pur}) nunca vale como token de acceso.
     */
    public static Optional<Reclamos> verificar(String token, Instant ahora, PublicKey clave, String idClave) {
        return cargaValida(token, ahora, clave, idClave)
                .filter(carga -> !carga.containsKey(RECLAMO_PROPOSITO))
                .flatMap(carga -> {
                    try {
                        return Optional.of(new Reclamos(UUID.fromString(carga.get("sub")),
                                UUID.fromString(carga.get("org")), carga.get("role"), carga.getOrDefault("email", ""),
                                carga.getOrDefault("name", ""),
                                Instant.ofEpochSecond(Long.parseLong(carga.get("exp")))));
                    } catch (IllegalArgumentException | NullPointerException e) {
                        return Optional.empty();
                    }
                });
    }

    /** Prueba de presencia valida (firma, emisor, vencimiento y {@code pur}); un token de acceso no lo es. */
    public static Optional<Presencia> verificarPresencia(String token, Instant ahora, PublicKey clave,
                                                         String idClave) {
        return cargaValida(token, ahora, clave, idClave)
                .filter(carga -> carga.containsKey(RECLAMO_PROPOSITO) && !carga.containsKey("role"))
                .flatMap(carga -> {
                    try {
                        return Optional.of(new Presencia(UUID.fromString(carga.get("sub")),
                                carga.get(RECLAMO_PROPOSITO), Instant.ofEpochSecond(Long.parseLong(carga.get("exp")))));
                    } catch (IllegalArgumentException | NullPointerException e) {
                        return Optional.empty();
                    }
                });
    }

    private static Optional<Map<String, String>> cargaValida(String token, Instant ahora, PublicKey clave,
                                                             String idClave) {
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
            return Optional.of(carga);
        } catch (IllegalArgumentException | NullPointerException | GeneralSecurityException e) {
            return Optional.empty();
        }
    }

    /** Id de clave (kid) de la cabecera, SIN verificar nada: solo para elegir con que clave verificar. */
    public static Optional<String> idClaveDe(String token) {
        try {
            int punto = token == null ? -1 : token.indexOf('.');
            if (punto <= 0) {
                return Optional.empty();
            }
            String cabecera = new String(D64.decode(token.substring(0, punto)), StandardCharsets.UTF_8);
            return Optional.ofNullable(leer(cabecera).get("kid"));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Id de una clave publica: los primeros 16 hex del SHA-256 de su SPKI, igual que identity. */
    public static String idDeClave(PublicKey clave) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(clave.getEncoded());
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** Clave Ed25519 desde su SPKI (como la publica TokenVerificationService). */
    public static PublicKey clavePublica(byte[] spki) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Clave publica Ed25519 no valida", e);
        }
    }

    private static String b64(String texto) {
        return B64.encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }

    /** JSON plano de cadenas y numeros, con los escapes de RFC 8259 para las cadenas. */
    static String json(Map<String, Object> campos) {
        StringBuilder salida = new StringBuilder("{");
        for (Map.Entry<String, Object> campo : campos.entrySet()) {
            if (salida.length() > 1) {
                salida.append(',');
            }
            salida.append('"').append(campo.getKey()).append("\":");
            Object valor = campo.getValue();
            if (valor instanceof Number) {
                salida.append(valor);
            } else {
                salida.append('"').append(escapar(String.valueOf(valor))).append('"');
            }
        }
        return salida.append('}').toString();
    }

    static Map<String, String> leer(String json) {
        Map<String, String> campos = new LinkedHashMap<>();
        Matcher m = CAMPO.matcher(json);
        while (m.find()) {
            campos.putIfAbsent(m.group(1), m.group(2) != null ? desescapar(m.group(2)) : m.group(3));
        }
        return campos;
    }

    private static String escapar(String texto) {
        StringBuilder salida = new StringBuilder(texto.length() + 8);
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '"' -> salida.append("\\\"");
                case '\\' -> salida.append("\\\\");
                case '\n' -> salida.append("\\n");
                case '\r' -> salida.append("\\r");
                case '\t' -> salida.append("\\t");
                default -> {
                    if (c < 0x20) {
                        salida.append(String.format("\\u%04x", (int) c));
                    } else {
                        salida.append(c);
                    }
                }
            }
        }
        return salida.toString();
    }

    private static String desescapar(String texto) {
        StringBuilder salida = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c != '\\') {
                salida.append(c);
                continue;
            }
            char siguiente = texto.charAt(++i);
            switch (siguiente) {
                case 'n' -> salida.append('\n');
                case 'r' -> salida.append('\r');
                case 't' -> salida.append('\t');
                case 'b' -> salida.append('\b');
                case 'f' -> salida.append('\f');
                case 'u' -> {
                    salida.append((char) Integer.parseInt(texto.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> salida.append(siguiente);
            }
        }
        return salida.toString();
    }
}

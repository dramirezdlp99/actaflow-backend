package com.actaflow.backend.identidad.grpc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import com.actaflow.backend.plataforma.rpc.FalloRpc;
import com.actaflow.backend.plataforma.rpc.FalloRpc.Violacion;
import com.actaflow.contracts.crypto.v1.CipherSuite;
import com.actaflow.contracts.crypto.v1.EncryptedBlob;
import com.actaflow.contracts.crypto.v1.PublicKey;
import com.google.protobuf.ByteString;

/**
 * Reglas de validacion del contrato (buf.validate), aplicadas antes de ejecutar cada caso de uso.
 * Los nombres de campo y de regla son los mismos que devuelve protovalidate en Python y
 * TypeScript, asi el cliente recibe el mismo detalle venga de donde venga el error.
 */
final class Reglas {

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern CORREO = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    static final Pattern TOTP = Pattern.compile("^[0-9]{6}$");
    static final Pattern RECUPERACION = Pattern.compile("^[A-Z0-9]{5}-[A-Z0-9]{5}$");
    /** Las claves de usuario son P-256 sin comprimir (65 bytes que empiezan por 0x04), por WebCrypto. */
    static final String ALGORITMO_CLAVE = "ECDH-P256";

    private final List<Violacion> violaciones = new ArrayList<>();

    private void agregar(String campo, String regla, String descripcion) {
        violaciones.add(new Violacion(campo, regla, descripcion));
    }

    Reglas correo(String campo, String valor) {
        if (valor.length() > 254) {
            agregar(campo, "string.max_len", "value length must be at most 254 characters");
        } else if (!CORREO.matcher(valor).matches()) {
            agregar(campo, "string.email", "value must be a valid email address");
        }
        return this;
    }

    Reglas uuid(String campo, String valor) {
        if (!UUID.matcher(valor).matches()) {
            agregar(campo, "string.uuid", "value must be a valid UUID");
        }
        return this;
    }

    Reglas largo(String campo, String valor, int minimo, int maximo) {
        int n = valor.codePointCount(0, valor.length());
        if (n < minimo) {
            agregar(campo, "string.min_len", "value length must be at least " + minimo + " characters");
        } else if (n > maximo) {
            agregar(campo, "string.max_len", "value length must be at most " + maximo + " characters");
        }
        return this;
    }

    Reglas patron(String campo, String valor, Pattern patron) {
        if (!patron.matcher(valor).matches()) {
            agregar(campo, "string.pattern", "value does not match regex pattern `" + patron.pattern() + "`");
        }
        return this;
    }

    Reglas bytes(String campo, ByteString valor, int largo) {
        if (valor.size() != largo) {
            agregar(campo, "bytes.len", "value length must be " + largo + " bytes");
        }
        return this;
    }

    Reglas minimo(String campo, int valor, int minimo) {
        if (valor < minimo) {
            agregar(campo, "int32.gte", "value must be greater than or equal to " + minimo);
        }
        return this;
    }

    Reglas requerido(String campo, boolean presente) {
        if (!presente) {
            agregar(campo, "required", "value is required");
        }
        return this;
    }

    Reglas cumple(String campo, String regla, boolean condicion, String descripcion) {
        if (!condicion) {
            agregar(campo, regla, descripcion);
        }
        return this;
    }

    Reglas blob(String campo, boolean presente, EncryptedBlob blob) {
        if (!presente) {
            return requerido(campo, false);
        }
        cumple(campo + ".suite", "enum.in", blob.getSuite() == CipherSuite.CIPHER_SUITE_AES_256_GCM,
                "value must be in list [1]");
        largo(campo + ".key_id", blob.getKeyId(), 1, 120);
        bytes(campo + ".nonce", blob.getNonce(), 12);
        int n = blob.getCiphertext().size();
        cumple(campo + ".ciphertext", "bytes.min_len", n >= 16, "value length must be at least 16 bytes");
        cumple(campo + ".ciphertext", "bytes.max_len", n <= 5_000_000, "value length must be at most 5000000 bytes");
        return largo(campo + ".plaintext_type", blob.getPlaintextType(), 0, 200);
    }

    Reglas clavePublica(String campo, boolean presente, PublicKey clave) {
        if (!presente) {
            return requerido(campo, false);
        }
        largo(campo + ".key_id", clave.getKeyId(), 1, 120);
        cumple(campo + ".algorithm", "public_key.algorithm", ALGORITMO_CLAVE.equals(clave.getAlgorithm()),
                "value must be " + ALGORITMO_CLAVE);
        ByteString bytes = clave.getPublicKey();
        return cumple(campo + ".public_key", "public_key.p256", bytes.size() == 65 && bytes.byteAt(0) == 0x04,
                "value must be an uncompressed P-256 point (65 bytes starting with 0x04)");
    }

    Reglas uuidsUnicos(String campo, List<String> valores, int minimo, int maximo) {
        if (valores.size() < minimo) {
            agregar(campo, "repeated.min_items", "value must contain at least " + minimo + " item(s)");
        } else if (valores.size() > maximo) {
            agregar(campo, "repeated.max_items", "value must contain no more than " + maximo + " item(s)");
        } else if (new HashSet<>(valores).size() != valores.size()) {
            agregar(campo, "repeated.unique", "repeated value must contain unique items");
        }
        for (int i = 0; i < valores.size(); i++) {
            uuid(campo + "[" + i + "]", valores.get(i));
        }
        return this;
    }

    /** Lanza INVALID_ARGUMENT con todas las reglas incumplidas. */
    void verificar() {
        if (!violaciones.isEmpty()) {
            throw new FalloRpc(FalloRpc.Tipo.ARGUMENTO_INVALIDO, "La peticion incumple el contrato",
                    violaciones);
        }
    }
}

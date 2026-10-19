package com.actaflow.backend.expedientes.grpc;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.actaflow.backend.plataforma.rpc.FalloRpc;
import com.actaflow.backend.plataforma.rpc.FalloRpc.Violacion;
import com.actaflow.contracts.common.v1.PageRequest;
import com.actaflow.contracts.crypto.v1.CipherSuite;
import com.actaflow.contracts.crypto.v1.EncryptedBlob;
import com.actaflow.contracts.crypto.v1.WrappedKey;
import com.google.protobuf.ProtocolMessageEnum;

/**
 * Reglas de validacion del contrato (buf.validate) de expedientes, aplicadas antes de cada caso de
 * uso. Campos y reglas con los mismos nombres que usa protovalidate, igual que en identity-service.
 */
final class Reglas {

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern CORREO = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    static final Pattern RADICADO = Pattern.compile("^[A-Z0-9][A-Z0-9-]{2,39}$");
    private static final int MAX_CIFRADO = 5_000_000;

    private final List<Violacion> violaciones = new ArrayList<>();

    private Reglas agregar(String campo, String regla, String descripcion) {
        violaciones.add(new Violacion(campo, regla, descripcion));
        return this;
    }

    Reglas cumple(String campo, String regla, boolean condicion, String descripcion) {
        return condicion ? this : agregar(campo, regla, descripcion);
    }

    Reglas uuid(String campo, String valor) {
        return cumple(campo, "string.uuid", UUID.matcher(valor).matches(), "value must be a valid UUID");
    }

    Reglas correo(String campo, String valor) {
        if (valor.length() > 254) {
            return agregar(campo, "string.max_len", "value length must be at most 254 characters");
        }
        return cumple(campo, "string.email", CORREO.matcher(valor).matches(), "value must be a valid email address");
    }

    Reglas largo(String campo, String valor, int minimo, int maximo) {
        int n = valor.codePointCount(0, valor.length());
        if (n < minimo) {
            return agregar(campo, "string.min_len", "value length must be at least " + minimo + " characters");
        }
        return cumple(campo, "string.max_len", n <= maximo, "value length must be at most " + maximo + " characters");
    }

    Reglas patron(String campo, String valor, Pattern patron) {
        return cumple(campo, "string.pattern", patron.matcher(valor).matches(),
                "value does not match regex pattern `" + patron.pattern() + "`");
    }

    /** Enum conocido por esta version del contrato (defined_only). */
    Reglas definido(String campo, boolean reconocido) {
        return cumple(campo, "enum.defined_only", reconocido, "value must be one of the defined enum values");
    }

    /** Enum conocido y distinto de UNSPECIFIED (defined_only + not_in: [0]). */
    Reglas elegido(String campo, ProtocolMessageEnum valor, boolean reconocido) {
        definido(campo, reconocido);
        return cumple(campo, "enum.not_in", !reconocido || valor.getNumber() != 0, "value must not be in list [0]");
    }

    Reglas requerido(String campo, boolean presente) {
        return cumple(campo, "required", presente, "value is required");
    }

    Reglas pagina(String campo, boolean presente, PageRequest pagina) {
        if (!presente) {
            return requerido(campo, false);
        }
        cumple(campo + ".page_size", "int32.gte_lte", pagina.getPageSize() >= 1 && pagina.getPageSize() <= 100,
                "value must be greater than or equal to 1 and less than or equal to 100");
        return cumple(campo + ".page_token", "string.max_len", pagina.getPageToken().length() <= 512,
                "value length must be at most 512 characters");
    }

    /** EncryptedBlob con AES-256-GCM (el contenido del caso, cifrado en el navegador). */
    Reglas blob(String campo, boolean presente, EncryptedBlob blob) {
        if (!presente) {
            return requerido(campo, false);
        }
        cumple(campo + ".suite", "enum.in", blob.getSuite() == CipherSuite.CIPHER_SUITE_AES_256_GCM,
                "value must be in list [1]");
        largo(campo + ".key_id", blob.getKeyId(), 1, 120);
        cumple(campo + ".nonce", "bytes.len", blob.getNonce().size() == 12, "value length must be 12 bytes");
        int n = blob.getCiphertext().size();
        cumple(campo + ".ciphertext", "bytes.min_len", n >= 16, "value length must be at least 16 bytes");
        cumple(campo + ".ciphertext", "bytes.max_len", n <= MAX_CIFRADO,
                "value length must be at most " + MAX_CIFRADO + " bytes");
        return largo(campo + ".plaintext_type", blob.getPlaintextType(), 0, 200);
    }

    /** Clave del caso envuelta con ECDH P-256 + HKDF + AES-256-GCM para la clave publica de alguien. */
    Reglas claveEnvuelta(String campo, boolean presente, WrappedKey clave) {
        if (!presente) {
            return requerido(campo, false);
        }
        cumple(campo + ".suite", "enum.in",
                clave.getSuite() == CipherSuite.CIPHER_SUITE_ECDH_P256_HKDF_SHA256_AES_256_GCM,
                "value must be in list [2]");
        largo(campo + ".recipient_key_id", clave.getRecipientKeyId(), 1, 120);
        cumple(campo + ".ephemeral_public_key", "bytes.len", clave.getEphemeralPublicKey().size() == 65
                && clave.getEphemeralPublicKey().byteAt(0) == 0x04, "value length must be 65 bytes");
        cumple(campo + ".nonce", "bytes.len", clave.getNonce().size() == 12, "value length must be 12 bytes");
        return cumple(campo + ".wrapped_key", "bytes.len", clave.getWrappedKey().size() == 48,
                "value length must be 48 bytes");
    }

    void verificar() {
        if (!violaciones.isEmpty()) {
            throw new FalloRpc(FalloRpc.Tipo.ARGUMENTO_INVALIDO, "La peticion no cumple el contrato", violaciones);
        }
    }
}

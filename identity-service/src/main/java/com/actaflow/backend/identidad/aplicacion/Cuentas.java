package com.actaflow.backend.identidad.aplicacion;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.actaflow.backend.identidad.dominio.Rol;
import com.actaflow.backend.identidad.persistencia.Bd;
import com.actaflow.contracts.crypto.v1.EncryptedBlob;
import com.actaflow.contracts.crypto.v1.PublicKey;
import com.actaflow.contracts.identity.v1.Account;
import com.actaflow.contracts.identity.v1.PrivateKeyKit;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;

/** Lectura de cuentas y su conversion a los mensajes del contrato. */
final class Cuentas {

    private Cuentas() {
    }

    /** Fila de la tabla account. */
    record Cuenta(UUID id, UUID organizacionId, String correo, String nombre, Rol rol, boolean activa,
                  byte[] sal, int iteraciones, byte[] hashVerificador, byte[] totpCifrado, UUID inscripcionTotp,
                  Instant totpConfirmadoEn, long ultimoPasoTotp, String idClavePublica, String algoritmoClave,
                  byte[] clavePublica, String huellaClave, byte[] privadaPorContrasena, byte[] privadaPorRecuperacion,
                  UUID claveRegistro, int fallos, Instant bloqueadaHasta, Instant ultimoIngreso, Instant creadaEn) {

        boolean confirmada() {
            return totpConfirmadoEn != null;
        }
    }

    static final String COLUMNAS = "id, organization_id, email, display_name, role, active, kdf_salt, kdf_iterations,"
            + " verifier_hash, totp_secret_enc, totp_enrollment_id, totp_confirmed_at, totp_last_step, public_key_id,"
            + " public_key_algorithm, public_key, public_key_fingerprint, private_key_by_password,"
            + " private_key_by_recovery, registration_key, failed_logins, locked_until, last_sign_in_at, created_at";

    static Cuenta leer(ResultSet f) throws SQLException {
        return new Cuenta(Bd.uuid(f, "id"), Bd.uuid(f, "organization_id"), f.getString("email"),
                f.getString("display_name"), Rol.valueOf(f.getString("role")), f.getBoolean("active"),
                f.getBytes("kdf_salt"), f.getInt("kdf_iterations"), f.getBytes("verifier_hash"),
                f.getBytes("totp_secret_enc"), Bd.uuid(f, "totp_enrollment_id"), Bd.instante(f, "totp_confirmed_at"),
                f.getLong("totp_last_step"), f.getString("public_key_id"), f.getString("public_key_algorithm"),
                f.getBytes("public_key"), f.getString("public_key_fingerprint"), f.getBytes("private_key_by_password"),
                f.getBytes("private_key_by_recovery"), Bd.uuid(f, "registration_key"), f.getInt("failed_logins"),
                Bd.instante(f, "locked_until"), Bd.instante(f, "last_sign_in_at"), Bd.instante(f, "created_at"));
    }

    static Optional<Cuenta> porId(Connection c, UUID id, boolean bloquear) throws SQLException {
        return Bd.uno(c, "select " + COLUMNAS + " from account where id = ?" + (bloquear ? " for update" : ""),
                Cuentas::leer, id);
    }

    static Optional<Cuenta> porCorreo(Connection c, String correo, boolean bloquear) throws SQLException {
        return Bd.uno(c, "select " + COLUMNAS + " from account where lower(email) = ?"
                + (bloquear ? " for update" : ""), Cuentas::leer, correo);
    }

    static Account contrato(Cuenta cuenta) {
        return Account.newBuilder()
                .setId(cuenta.id().toString())
                .setEmail(cuenta.correo())
                .setDisplayName(cuenta.nombre())
                .setRole(cuenta.rol().contrato())
                .setOrganizationId(cuenta.organizacionId().toString())
                .setPublicKeyId(cuenta.idClavePublica())
                .setCreatedAt(marca(cuenta.creadaEn()))
                .build();
    }

    static PublicKey clavePublica(Cuenta cuenta) {
        return PublicKey.newBuilder()
                .setKeyId(cuenta.idClavePublica())
                .setAlgorithm(cuenta.algoritmoClave())
                .setPublicKey(ByteString.copyFrom(cuenta.clavePublica()))
                .setFingerprintSha256(cuenta.huellaClave())
                .build();
    }

    static PrivateKeyKit kit(Cuenta cuenta) {
        return PrivateKeyKit.newBuilder()
                .setPublicKey(clavePublica(cuenta))
                .setPrivateKeyByPassword(blob(cuenta.privadaPorContrasena()))
                .setPrivateKeyByRecovery(blob(cuenta.privadaPorRecuperacion()))
                .build();
    }

    static Timestamp marca(Instant instante) {
        if (instante == null) {
            return Timestamp.getDefaultInstance();
        }
        return Timestamp.newBuilder().setSeconds(instante.getEpochSecond()).setNanos(instante.getNano()).build();
    }

    private static EncryptedBlob blob(byte[] guardado) {
        try {
            return EncryptedBlob.parseFrom(guardado);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Clave privada guardada ilegible", e);
        }
    }
}

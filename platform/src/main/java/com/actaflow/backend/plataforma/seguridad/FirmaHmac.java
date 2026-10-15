package com.actaflow.backend.plataforma.seguridad;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.actaflow.backend.plataforma.grpc.Metadatos;

import io.grpc.Metadata;

/**
 * Firma HMAC-SHA256 de las llamadas gRPC entre servicios de ActaFlow.
 *
 * <p>Mensaje canonico, identico en el BFF (TypeScript), los microservicios (Java) y el motor (Python):
 * {@code timestamp \n RPC \n /<paquete.Servicio>/<Metodo> \n sha256_hex(bytes protobuf de la peticion)}.
 * Viaja en los metadatos {@code x-actaflow-timestamp} y {@code x-actaflow-signature}.
 * Firmar los bytes exactos impide alterar el mensaje sin invalidar la firma.
 */
public final class FirmaHmac {

    public static final byte[] SIN_CUERPO = new byte[0];

    private static final String ALGORITMO = "HmacSHA256";
    private static final int MAX_DIGITOS_TIMESTAMP = 12;
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] secreto;
    private final long toleranciaSegundos;
    private final Clock reloj;

    public FirmaHmac(String secreto, long toleranciaSegundos, Clock reloj) {
        if (secreto == null || secreto.length() < 32) {
            throw new IllegalArgumentException("El secreto HMAC debe tener al menos 32 caracteres");
        }
        this.secreto = secreto.getBytes(StandardCharsets.UTF_8);
        this.toleranciaSegundos = toleranciaSegundos;
        this.reloj = reloj;
    }

    /** Ruta gRPC con barra inicial, como la usa el mensaje canonico: {@code /paquete.Servicio/Metodo}. */
    public static String ruta(String nombreCompletoMetodo) {
        return nombreCompletoMetodo.startsWith("/") ? nombreCompletoMetodo : "/" + nombreCompletoMetodo;
    }

    public static String mensajeCanonico(String timestamp, String metodoGrpc, byte[] cuerpo) {
        return timestamp + "\nRPC\n" + ruta(metodoGrpc) + "\n" + sha256Hex(cuerpo);
    }

    public String firmar(String timestamp, String metodoGrpc, byte[] cuerpo) {
        try {
            Mac mac = Mac.getInstance(ALGORITMO);
            mac.init(new SecretKeySpec(secreto, ALGORITMO));
            byte[] firma = mac.doFinal(mensajeCanonico(timestamp, metodoGrpc, cuerpo)
                    .getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(firma);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no disponible en la JVM", e);
        }
    }

    /** Metadatos gRPC con la firma de una llamada saliente, usando la hora actual. */
    public Metadata metadatos(String metodoGrpc, byte[] cuerpo) {
        String timestamp = Long.toString(reloj.instant().getEpochSecond());
        Metadata metadatos = new Metadata();
        metadatos.put(Metadatos.TIMESTAMP, timestamp);
        metadatos.put(Metadatos.FIRMA, firmar(timestamp, metodoGrpc, cuerpo));
        return metadatos;
    }

    /**
     * Verifica una llamada entrante.
     *
     * @throws FirmaInvalidaException con el motivo del rechazo (solo para el log)
     */
    public void verificar(String timestamp, String firma, String metodoGrpc, byte[] cuerpo) {
        if (timestamp == null || timestamp.isBlank() || firma == null || firma.isBlank()) {
            throw new FirmaInvalidaException(FirmaInvalidaException.Motivo.FALTA_METADATO);
        }
        if (timestamp.length() > MAX_DIGITOS_TIMESTAMP || !timestamp.chars().allMatch(Character::isDigit)) {
            throw new FirmaInvalidaException(FirmaInvalidaException.Motivo.TIMESTAMP_INVALIDO);
        }
        long ahora = reloj.instant().getEpochSecond();
        if (Math.abs(ahora - Long.parseLong(timestamp)) > toleranciaSegundos) {
            throw new FirmaInvalidaException(FirmaInvalidaException.Motivo.FUERA_DE_VENTANA);
        }
        byte[] esperada = firmar(timestamp, metodoGrpc, cuerpo).getBytes(StandardCharsets.US_ASCII);
        byte[] recibida = firma.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(esperada, recibida)) {
            throw new FirmaInvalidaException(FirmaInvalidaException.Motivo.FIRMA_INCORRECTA);
        }
    }

    static String sha256Hex(byte[] datos) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 no disponible en la JVM", e);
        }
    }
}

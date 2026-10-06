package com.actaflow.backend.compartido.seguridad;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Firma HMAC-SHA256 entre servicios de ActaFlow (BFF, backend y motor de IA).
 *
 * <p>Mensaje canonico, identico en los tres repos:
 * {@code timestamp \n METODO \n ruta[?consulta] \n sha256_hex(cuerpo)}.
 * Clase sin dependencias de Spring: es dominio puro y se prueba con vectores compartidos.
 */
public final class FirmaHmac {

    public static final String CABECERA_TIMESTAMP = "X-ActaFlow-Timestamp";
    public static final String CABECERA_FIRMA = "X-ActaFlow-Signature";

    private static final String ALGORITMO = "HmacSHA256";
    private static final int MAX_DIGITOS_TIMESTAMP = 12;
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] secreto;
    private final long toleranciaSegundos;
    private final Clock reloj;

    public FirmaHmac(String secreto, long toleranciaSegundos, Clock reloj) {
        this.secreto = secreto.getBytes(StandardCharsets.UTF_8);
        this.toleranciaSegundos = toleranciaSegundos;
        this.reloj = reloj;
    }

    public static String mensajeCanonico(String timestamp, String metodo, String ruta, byte[] cuerpo) {
        return timestamp + "\n" + metodo.toUpperCase(Locale.ROOT) + "\n" + ruta + "\n" + sha256Hex(cuerpo);
    }

    public String firmar(String timestamp, String metodo, String ruta, byte[] cuerpo) {
        try {
            Mac mac = Mac.getInstance(ALGORITMO);
            mac.init(new SecretKeySpec(secreto, ALGORITMO));
            byte[] firma = mac.doFinal(mensajeCanonico(timestamp, metodo, ruta, cuerpo)
                    .getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(firma);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no disponible en la JVM", e);
        }
    }

    /** Cabeceras listas para una peticion saliente firmada con la hora actual. */
    public Map<String, String> cabeceras(String metodo, String ruta, byte[] cuerpo) {
        String timestamp = Long.toString(reloj.instant().getEpochSecond());
        return Map.of(CABECERA_TIMESTAMP, timestamp, CABECERA_FIRMA, firmar(timestamp, metodo, ruta, cuerpo));
    }

    /**
     * Verifica una peticion entrante.
     *
     * @throws FirmaInvalidaException con el motivo del rechazo (solo para el log)
     */
    public void verificar(String timestamp, String firma, String metodo, String ruta, byte[] cuerpo) {
        if (timestamp == null || timestamp.isBlank() || firma == null || firma.isBlank()) {
            throw new FirmaInvalidaException(FirmaInvalidaException.Motivo.FALTA_CABECERA);
        }
        if (timestamp.length() > MAX_DIGITOS_TIMESTAMP || !timestamp.chars().allMatch(Character::isDigit)) {
            throw new FirmaInvalidaException(FirmaInvalidaException.Motivo.TIMESTAMP_INVALIDO);
        }
        long ahora = reloj.instant().getEpochSecond();
        if (Math.abs(ahora - Long.parseLong(timestamp)) > toleranciaSegundos) {
            throw new FirmaInvalidaException(FirmaInvalidaException.Motivo.FUERA_DE_VENTANA);
        }
        byte[] esperada = firmar(timestamp, metodo, ruta, cuerpo).getBytes(StandardCharsets.US_ASCII);
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

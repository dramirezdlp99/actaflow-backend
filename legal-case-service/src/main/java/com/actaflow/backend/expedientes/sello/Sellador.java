package com.actaflow.backend.expedientes.sello;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sello de integridad de las actas: firma CMS (PKCS #7, RFC 5652) SEPARADA sobre el SHA-256 del PDF,
 * con el certificado propio de ActaFlow dentro de la firma. Para firmar basta el hash: legal-case
 * nunca ve el PDF (lo genera el motor de IA y viaja cifrado).
 *
 * <p>La clave ECDSA P-256 se deriva de ACTAFLOW_SEAL_SECRET (como la clave de los tokens en identity),
 * asi sobrevive a los reinicios sin guardar archivos; el certificado autofirmado tambien es fijo.
 * Cualquiera puede comprobar un PDF con {@code openssl cms -verify -binary -inform DER -in sello.p7s
 * -content acta.pdf -noverify}.
 *
 * <p>Es un sello de integridad, no una firma digital certificada (Ley 527 de 1999: exige un
 * certificado de una entidad acreditada ante ONAC).
 */
public final class Sellador {

    private static final String SHA256 = "2.16.840.1.101.3.4.2.1";
    private static final String ECDSA_SHA256 = "1.2.840.10045.4.3.2";
    private static final String DATOS = "1.2.840.113549.1.7.1";
    private static final String DATOS_FIRMADOS = "1.2.840.113549.1.7.2";
    private static final String ATRIBUTO_TIPO = "1.2.840.113549.1.9.3";
    private static final String ATRIBUTO_RESUMEN = "1.2.840.113549.1.9.4";
    private static final String ATRIBUTO_HORA = "1.2.840.113549.1.9.5";
    /** Vigencia fija del certificado: el mismo secreto produce siempre el mismo certificado. */
    private static final Instant DESDE = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant HASTA = Instant.parse("2046-01-01T00:00:00Z");
    public static final String FIRMANTE = "CN=ActaFlow - Sello de integridad,O=ActaFlow";

    private final KeyPair claves;
    private final X509Certificate certificado;
    private final String huella;

    /** Sello emitido: la firma CMS y los datos publicos del certificado. */
    public record Sello(byte[] cms, String firmante, String huellaCertificado) {
    }

    public Sellador(String secreto) {
        if (secreto == null || secreto.length() < 32) {
            throw new IllegalArgumentException("ACTAFLOW_SEAL_SECRET debe tener al menos 32 caracteres");
        }
        try {
            byte[] semilla = hmac(secreto.getBytes(StandardCharsets.UTF_8), "actaflow/sello/clave/v1");
            KeyPairGenerator generador = KeyPairGenerator.getInstance("EC");
            generador.initialize(new ECGenParameterSpec("secp256r1"), new Determinista(semilla));
            this.claves = generador.generateKeyPair();
            this.certificado = autofirmado(new BigInteger(1, Arrays.copyOf(hmac(semilla, "serie"), 16)));
            this.huella = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(certificado.getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo preparar el sello", e);
        }
    }

    public X509Certificate certificado() {
        return certificado;
    }

    public String huellaCertificado() {
        return huella;
    }

    /** Firma CMS separada para un documento cuyo SHA-256 es {@code sha256}. */
    public Sello sellar(byte[] sha256, Instant ahora) {
        if (sha256.length != 32) {
            throw new IllegalArgumentException("Se esperaba un SHA-256 de 32 bytes");
        }
        try {
            byte[] atributos = atributosFirmados(sha256, ahora);
            Signature firma = Signature.getInstance("SHA256withECDSA");
            firma.initSign(claves.getPrivate());
            firma.update(atributos);
            byte[] valor = firma.sign();
            byte[] algoritmoResumen = Der.secuencia(Der.oid(SHA256));
            byte[] firmante = Der.secuencia(
                    Der.entero(1),
                    Der.secuencia(certificado.getIssuerX500Principal().getEncoded(),
                            Der.entero(certificado.getSerialNumber())),
                    algoritmoResumen,
                    // [0] IMPLICIT: los mismos atributos firmados, con otra etiqueta.
                    reetiquetar(atributos, 0xA0),
                    Der.secuencia(Der.oid(ECDSA_SHA256)),
                    Der.octetos(valor));
            byte[] firmados = Der.secuencia(
                    Der.entero(1),
                    Der.conjunto(algoritmoResumen),
                    Der.secuencia(Der.oid(DATOS)),
                    Der.contexto(0, certificado.getEncoded()),
                    Der.conjunto(firmante));
            byte[] cms = Der.secuencia(Der.oid(DATOS_FIRMADOS), Der.contexto(0, firmados));
            return new Sello(cms, certificado.getSubjectX500Principal().getName(), huella);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo sellar", e);
        }
    }

    /** SET de atributos firmados (tipo de contenido, resumen y hora): es lo que cubre la firma. */
    static byte[] atributosFirmados(byte[] sha256, Instant ahora) {
        return Der.conjunto(
                Der.secuencia(Der.oid(ATRIBUTO_TIPO), Der.conjunto(Der.oid(DATOS))),
                Der.secuencia(Der.oid(ATRIBUTO_HORA), Der.conjunto(Der.tiempo(ahora))),
                Der.secuencia(Der.oid(ATRIBUTO_RESUMEN), Der.conjunto(Der.octetos(sha256))));
    }

    private X509Certificate autofirmado(BigInteger serie) throws GeneralSecurityException {
        byte[] nombre = Der.secuencia(
                Der.conjunto(Der.secuencia(Der.oid("2.5.4.3"), Der.utf8("ActaFlow - Sello de integridad"))),
                Der.conjunto(Der.secuencia(Der.oid("2.5.4.10"), Der.utf8("ActaFlow"))));
        byte[] algoritmo = Der.secuencia(Der.oid(ECDSA_SHA256));
        // keyUsage critico: digitalSignature y nonRepudiation (bits 0 y 1).
        byte[] usos = Der.secuencia(Der.oid("2.5.29.15"), Der.booleano(true),
                Der.octetos(Der.tlv(0x03, new byte[] {6, (byte) 0xC0})));
        byte[] sinCa = Der.secuencia(Der.oid("2.5.29.19"), Der.octetos(Der.secuencia()));
        byte[] datos = Der.secuencia(
                Der.contexto(0, Der.entero(2)),
                Der.entero(serie),
                algoritmo,
                nombre,
                Der.secuencia(Der.tiempo(DESDE), Der.tiempo(HASTA)),
                nombre,
                claves.getPublic().getEncoded(),
                Der.tlv(0xA3, Der.secuencia(usos, sinCa)));
        Signature firma = Signature.getInstance("SHA256withECDSA");
        firma.initSign(claves.getPrivate(), new Determinista(hmac(datos, "firma-certificado")));
        firma.update(datos);
        byte[] der = Der.secuencia(datos, algoritmo, Der.bits(firma.sign()));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] reetiquetar(byte[] der, int etiqueta) {
        byte[] copia = der.clone();
        copia[0] = (byte) etiqueta;
        return copia;
    }

    private static byte[] hmac(byte[] clave, String uso) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clave, "HmacSHA256"));
            return mac.doFinal(uso.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] clave, byte[] datos) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clave, "HmacSHA256"));
            return mac.doFinal(datos);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fuente de bytes deterministica (HMAC-SHA-256 en modo contador) a partir de una semilla. */
    private static final class Determinista extends SecureRandom {
        private static final long serialVersionUID = 1L;
        private final byte[] semilla;
        private long contador;

        Determinista(byte[] semilla) {
            super(new byte[0]);
            this.semilla = semilla.clone();
        }

        @Override
        public void nextBytes(byte[] destino) {
            int i = 0;
            while (i < destino.length) {
                byte[] bloque = hmac(semilla, Long.toString(contador++));
                int n = Math.min(bloque.length, destino.length - i);
                System.arraycopy(bloque, 0, destino, i, n);
                i += n;
            }
        }
    }
}

package com.actaflow.backend.expedientes.sello;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Codificacion DER minima (ASN.1) para armar el certificado y la firma CMS del sello sin librerias
 * externas: solo los tipos que usan RFC 5280 y RFC 5652.
 */
final class Der {

    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private Der() {
    }

    static byte[] tlv(int etiqueta, byte[]... partes) {
        ByteArrayOutputStream contenido = new ByteArrayOutputStream();
        for (byte[] parte : partes) {
            contenido.writeBytes(parte);
        }
        byte[] valor = contenido.toByteArray();
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        salida.write(etiqueta);
        int n = valor.length;
        if (n < 0x80) {
            salida.write(n);
        } else {
            byte[] largo = BigInteger.valueOf(n).toByteArray();
            int desde = largo[0] == 0 ? 1 : 0;
            salida.write(0x80 | (largo.length - desde));
            salida.write(largo, desde, largo.length - desde);
        }
        salida.writeBytes(valor);
        return salida.toByteArray();
    }

    static byte[] secuencia(byte[]... partes) {
        return tlv(0x30, partes);
    }

    /** SET OF en DER: los elementos van ordenados por su codificacion. */
    static byte[] conjunto(byte[]... partes) {
        List<byte[]> ordenados = new ArrayList<>(List.of(partes));
        ordenados.sort(Arrays::compareUnsigned);
        return tlv(0x31, ordenados.toArray(byte[][]::new));
    }

    static byte[] entero(BigInteger valor) {
        return tlv(0x02, valor.toByteArray());
    }

    static byte[] entero(long valor) {
        return entero(BigInteger.valueOf(valor));
    }

    static byte[] octetos(byte[] valor) {
        return tlv(0x04, valor);
    }

    static byte[] bits(byte[] valor) {
        byte[] conRelleno = new byte[valor.length + 1];
        System.arraycopy(valor, 0, conRelleno, 1, valor.length);
        return tlv(0x03, conRelleno);
    }

    static byte[] utf8(String texto) {
        return tlv(0x0C, texto.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] tiempo(Instant instante) {
        return tlv(0x17, UTC_TIME.format(instante).getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] booleano(boolean valor) {
        return tlv(0x01, new byte[] {(byte) (valor ? 0xFF : 0x00)});
    }

    /** Contexto [n] construido: EXPLICIT, o IMPLICIT de un SET/SEQUENCE. */
    static byte[] contexto(int numero, byte[]... partes) {
        return tlv(0xA0 | numero, partes);
    }

    static byte[] oid(String puntos) {
        String[] arcos = puntos.split("\\.");
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        salida.write(Integer.parseInt(arcos[0]) * 40 + Integer.parseInt(arcos[1]));
        for (int i = 2; i < arcos.length; i++) {
            long arco = Long.parseLong(arcos[i]);
            byte[] grupos = new byte[10];
            int n = 0;
            do {
                grupos[n++] = (byte) (arco & 0x7F);
                arco >>>= 7;
            } while (arco > 0);
            for (int j = n - 1; j >= 0; j--) {
                salida.write(grupos[j] | (j > 0 ? 0x80 : 0));
            }
        }
        return tlv(0x06, salida.toByteArray());
    }
}

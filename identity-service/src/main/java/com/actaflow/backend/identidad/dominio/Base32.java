package com.actaflow.backend.identidad.dominio;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** Base32 (RFC 4648) sin relleno, el formato que leen las apps de autenticacion para TOTP. */
public final class Base32 {

    private static final String ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    public static String codificar(byte[] datos) {
        StringBuilder salida = new StringBuilder((datos.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : datos) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                salida.append(ALFABETO.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            salida.append(ALFABETO.charAt((buffer << (5 - bits)) & 31));
        }
        return salida.toString();
    }

    public static byte[] decodificar(String texto) {
        String limpio = texto.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : limpio.toCharArray()) {
            int valor = ALFABETO.indexOf(c);
            if (valor < 0) {
                throw new IllegalArgumentException("Caracter no valido en Base32");
            }
            buffer = (buffer << 5) | valor;
            bits += 5;
            if (bits >= 8) {
                salida.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return salida.toByteArray();
    }
}

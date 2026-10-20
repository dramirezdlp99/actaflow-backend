package com.actaflow.backend.expedientes.plantillas;

/**
 * Numeros escritos en letras, como se usan en las actas: "un millon quinientos mil pesos",
 * "veintiun dias", "dos mil veintiseis". Escala larga del espanol (millon = 10^6, billon = 10^12).
 */
public final class EnLetras {

    private static final String[] HASTA_29 = {"", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete",
        "ocho", "nueve", "diez", "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete",
        "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidós", "veintitrés", "veinticuatro",
        "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve"};
    private static final String[] DECENAS = {"", "", "", "treinta", "cuarenta", "cincuenta", "sesenta",
        "setenta", "ochenta", "noventa"};
    private static final String[] CENTENAS = {"", "ciento", "doscientos", "trescientos", "cuatrocientos",
        "quinientos", "seiscientos", "setecientos", "ochocientos", "novecientos"};

    private static final long MILLON = 1_000_000L;
    private static final long BILLON = 1_000_000_000_000L;

    private EnLetras() {
    }

    /** El numero solo: 21 = "veintiuno". */
    public static String numero(long n) {
        return escribir(n, false);
    }

    /**
     * El numero delante de un sustantivo masculino: 21 = "veintiun", 1 = "un" (veintiun dias, un
     * peso).
     */
    public static String delante(long n) {
        return escribir(n, true);
    }

    /** Si el numero termina en millon(es) o billon(es) y pide "de": "un millon de pesos". */
    public static boolean pideDe(long n) {
        return n != 0 && Math.abs(n % MILLON) == 0;
    }

    private static String escribir(long n, boolean apocope) {
        if (n == 0) {
            return "cero";
        }
        if (n < 0) {
            return n == Long.MIN_VALUE ? "menos " + escribir(Long.MAX_VALUE, apocope)
                    : "menos " + escribir(-n, apocope);
        }
        StringBuilder texto = new StringBuilder();
        long billones = n / BILLON;
        long resto = n % BILLON;
        if (billones > 0) {
            texto.append(billones == 1 ? "un billón" : escribir(billones, true) + " billones");
        }
        long millones = resto / MILLON;
        if (millones > 0) {
            separar(texto).append(millones == 1 ? "un millón" : menorQueMillon(millones, true) + " millones");
        }
        long unidades = resto % MILLON;
        if (unidades > 0) {
            separar(texto).append(menorQueMillon(unidades, apocope));
        }
        return texto.toString();
    }

    private static StringBuilder separar(StringBuilder texto) {
        return texto.isEmpty() ? texto : texto.append(' ');
    }

    private static String menorQueMillon(long n, boolean apocope) {
        long miles = n / 1000;
        long resto = n % 1000;
        StringBuilder texto = new StringBuilder();
        if (miles > 0) {
            texto.append(miles == 1 ? "mil" : cientos((int) miles, true) + " mil");
        }
        if (resto > 0) {
            separar(texto).append(cientos((int) resto, apocope));
        }
        return texto.toString();
    }

    private static String cientos(int n, boolean apocope) {
        int c = n / 100;
        int r = n % 100;
        if (c == 1 && r == 0) {
            return "cien";
        }
        String decenas = decenas(r, apocope);
        if (c == 0) {
            return decenas;
        }
        return decenas.isEmpty() ? CENTENAS[c] : CENTENAS[c] + " " + decenas;
    }

    private static String decenas(int n, boolean apocope) {
        if (n < 30) {
            if (apocope && n == 1) {
                return "un";
            }
            if (apocope && n == 21) {
                return "veintiún";
            }
            return HASTA_29[n];
        }
        int u = n % 10;
        if (u == 0) {
            return DECENAS[n / 10];
        }
        return DECENAS[n / 10] + " y " + (apocope && u == 1 ? "un" : HASTA_29[u]);
    }
}

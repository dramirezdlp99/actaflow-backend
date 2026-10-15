package com.actaflow.backend.plataforma.grpcweb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.grpc.Metadata;

/**
 * Tramas del protocolo gRPC-Web binario ({@code application/grpc-web+proto}).
 *
 * <p>Cada trama: 1 byte de banderas + 4 bytes de longitud (big-endian) + contenido. Bandera 0x00:
 * mensaje protobuf. Bandera 0x80: trailers en texto {@code clave:valor\r\n} (aqui viaja grpc-status).
 * La compresion (0x01) no se admite: el contenido de ActaFlow ya va cifrado.
 */
public final class TramasGrpcWeb {

    public static final String TIPO = "application/grpc-web+proto";
    public static final int BANDERA_DATOS = 0x00;
    public static final int BANDERA_COMPRIMIDA = 0x01;
    public static final int BANDERA_TRAILERS = 0x80;
    private static final int CABECERA = 5;

    private TramasGrpcWeb() {
    }

    /** Una trama leida. */
    public record Trama(int bandera, byte[] contenido) {

        public boolean esTrailers() {
            return (bandera & BANDERA_TRAILERS) != 0;
        }
    }

    /** El cuerpo no es una secuencia valida de tramas. */
    public static final class TramaInvalidaException extends Exception {

        public TramaInvalidaException(String mensaje) {
            super(mensaje);
        }
    }

    public static byte[] codificar(int bandera, byte[] contenido) {
        ByteBuffer buffer = ByteBuffer.allocate(CABECERA + contenido.length);
        buffer.put((byte) bandera).putInt(contenido.length).put(contenido);
        return buffer.array();
    }

    public static byte[] datos(byte[] mensaje) {
        return codificar(BANDERA_DATOS, mensaje);
    }

    /**
     * Lee la siguiente trama de un flujo, o null si el flujo termino justo entre tramas.
     */
    public static Trama leer(InputStream entrada, int maxMensaje) throws IOException, TramaInvalidaException {
        int bandera = entrada.read();
        if (bandera < 0) {
            return null;
        }
        byte[] longitudBytes = entrada.readNBytes(4);
        if (longitudBytes.length < 4) {
            throw new TramaInvalidaException("Cabecera de trama incompleta");
        }
        long longitud = Integer.toUnsignedLong(ByteBuffer.wrap(longitudBytes).getInt());
        if (longitud > maxMensaje) {
            throw new TramaInvalidaException("Trama de " + longitud + " bytes supera el maximo " + maxMensaje);
        }
        if ((bandera & BANDERA_COMPRIMIDA) != 0) {
            throw new TramaInvalidaException("Compresion no admitida");
        }
        if (bandera != BANDERA_DATOS && bandera != BANDERA_TRAILERS) {
            throw new TramaInvalidaException("Bandera desconocida 0x" + Integer.toHexString(bandera));
        }
        byte[] contenido = entrada.readNBytes((int) longitud);
        if (contenido.length < longitud) {
            throw new TramaInvalidaException("Trama truncada");
        }
        return new Trama(bandera, contenido);
    }

    /** Separa un cuerpo completo en tramas. */
    public static List<Trama> decodificar(byte[] cuerpo, int maxMensaje) throws TramaInvalidaException {
        List<Trama> tramas = new ArrayList<>();
        try (InputStream entrada = new ByteArrayInputStream(cuerpo)) {
            Trama trama;
            while ((trama = leer(entrada, maxMensaje)) != null) {
                tramas.add(trama);
            }
        } catch (EOFException e) {
            throw new TramaInvalidaException("Trama truncada");
        } catch (IOException e) {
            throw new TramaInvalidaException(e.getMessage());
        }
        return tramas;
    }

    /** Trama final con grpc-status, grpc-message (codificado en porcentaje) y los metadatos extra. */
    public static byte[] trailers(int codigo, String mensaje, Metadata extra) {
        StringBuilder texto = new StringBuilder("grpc-status:").append(codigo).append("\r\n");
        if (mensaje != null && !mensaje.isEmpty()) {
            texto.append("grpc-message:").append(codificarPorcentaje(mensaje)).append("\r\n");
        }
        if (extra != null) {
            for (String clave : extra.keys()) {
                String minuscula = clave.toLowerCase(Locale.ROOT);
                if (minuscula.equals("grpc-status") || minuscula.equals("grpc-message")) {
                    continue;
                }
                if (minuscula.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                    var llave = Metadata.Key.of(minuscula, Metadata.BINARY_BYTE_MARSHALLER);
                    for (byte[] valor : extra.getAll(llave)) {
                        texto.append(minuscula).append(':')
                                .append(Base64.getEncoder().withoutPadding().encodeToString(valor)).append("\r\n");
                    }
                } else {
                    var llave = Metadata.Key.of(minuscula, Metadata.ASCII_STRING_MARSHALLER);
                    for (String valor : extra.getAll(llave)) {
                        texto.append(minuscula).append(':').append(valor).append("\r\n");
                    }
                }
            }
        }
        return codificar(BANDERA_TRAILERS, texto.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Interpreta una trama de trailers; los valores -bin se devuelven en base64 tal como llegaron. */
    public static Map<String, String> leerTrailers(byte[] contenido) {
        Map<String, String> resultado = new LinkedHashMap<>();
        for (String linea : new String(contenido, StandardCharsets.UTF_8).split("\r\n")) {
            int dosPuntos = linea.indexOf(':');
            if (dosPuntos <= 0) {
                continue;
            }
            String clave = linea.substring(0, dosPuntos).strip().toLowerCase(Locale.ROOT);
            resultado.put(clave, linea.substring(dosPuntos + 1).strip());
        }
        resultado.computeIfPresent("grpc-message", (k, v) -> decodificarPorcentaje(v));
        return resultado;
    }

    /** Codificacion de grpc-message segun la especificacion de gRPC (porcentaje sobre UTF-8). */
    static String codificarPorcentaje(String texto) {
        StringBuilder salida = new StringBuilder();
        for (byte b : texto.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c >= 0x20 && c <= 0x7E && c != '%') {
                salida.append((char) c);
            } else {
                salida.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return salida.toString();
    }

    static String decodificarPorcentaje(String texto) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c == '%' && i + 2 < texto.length()) {
                try {
                    bytes.write(Integer.parseInt(texto.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException e) {
                    // No es una secuencia valida: se deja tal cual.
                }
            }
            bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}

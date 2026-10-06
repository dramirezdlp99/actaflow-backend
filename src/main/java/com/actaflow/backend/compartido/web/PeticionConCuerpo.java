package com.actaflow.backend.compartido.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Envoltura que guarda el cuerpo ya leido para poder verificar la firma y, despues,
 * dejar que Spring MVC lo vuelva a leer.
 */
public class PeticionConCuerpo extends HttpServletRequestWrapper {

    private final byte[] cuerpo;

    public PeticionConCuerpo(HttpServletRequest peticion, byte[] cuerpo) {
        super(peticion);
        this.cuerpo = cuerpo.clone();
    }

    public byte[] cuerpo() {
        return cuerpo.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream origen = new ByteArrayInputStream(cuerpo);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return origen.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Lectura asincrona no soportada");
            }

            @Override
            public int read() {
                return origen.read();
            }

            @Override
            public int read(byte[] destino, int desde, int cantidad) {
                return origen.read(destino, desde, cantidad);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String codificacion = getCharacterEncoding();
        Charset charset = codificacion != null ? Charset.forName(codificacion) : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public int getContentLength() {
        return cuerpo.length;
    }

    @Override
    public long getContentLengthLong() {
        return cuerpo.length;
    }
}

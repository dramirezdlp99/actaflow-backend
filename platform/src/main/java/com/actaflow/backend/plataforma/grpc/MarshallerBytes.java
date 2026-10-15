package com.actaflow.backend.plataforma.grpc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import io.grpc.MethodDescriptor;

/** Marshaller identidad: los mensajes son sus propios bytes protobuf. */
public final class MarshallerBytes implements MethodDescriptor.Marshaller<byte[]> {

    public static final MarshallerBytes INSTANCIA = new MarshallerBytes();

    private MarshallerBytes() {
    }

    @Override
    public InputStream stream(byte[] valor) {
        return new ByteArrayInputStream(valor);
    }

    @Override
    public byte[] parse(InputStream flujo) {
        try (flujo) {
            return flujo.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

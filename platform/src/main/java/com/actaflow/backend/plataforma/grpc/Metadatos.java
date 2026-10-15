package com.actaflow.backend.plataforma.grpc;

import io.grpc.Metadata;

/** Claves de metadatos gRPC que usa ActaFlow (siempre en minusculas, como exige gRPC). */
public final class Metadatos {

    /** Segundos Unix en que se firmo la llamada. */
    public static final Metadata.Key<String> TIMESTAMP =
            Metadata.Key.of("x-actaflow-timestamp", Metadata.ASCII_STRING_MARSHALLER);

    /** Firma HMAC-SHA256 en hexadecimal. */
    public static final Metadata.Key<String> FIRMA =
            Metadata.Key.of("x-actaflow-signature", Metadata.ASCII_STRING_MARSHALLER);

    /** Transporte por el que llego la llamada; solo lo pone la pasarela gRPC-Web. */
    public static final Metadata.Key<String> TRANSPORTE =
            Metadata.Key.of("x-actaflow-transport", Metadata.ASCII_STRING_MARSHALLER);

    /** Token de sesion del usuario (llega desde el BFF a partir del bloque de identidad). */
    public static final Metadata.Key<String> AUTORIZACION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /** Valor de TRANSPORTE cuando la llamada entro por gRPC-Web. */
    public static final String VALOR_GRPC_WEB = "grpc-web";

    private Metadatos() {
    }
}

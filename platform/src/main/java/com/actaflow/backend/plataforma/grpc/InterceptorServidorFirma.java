package com.actaflow.backend.plataforma.grpc;

import java.lang.System.Logger.Level;

import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.backend.plataforma.seguridad.FirmaInvalidaException;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * Verifica la firma HMAC de cada llamada entrante y registra el transporte en el contexto.
 *
 * <p>Se aplica sobre una definicion de servicio que trabaja con los bytes crudos
 * ({@link io.grpc.ServerInterceptors#useMarshalledMessages}), asi la firma se comprueba sobre
 * exactamente lo que llego por la red, antes de deserializar. En los metodos cuyo cliente envia un
 * solo mensaje se firma ese mensaje; en los de streaming del cliente, un mensaje vacio.
 */
public final class InterceptorServidorFirma implements ServerInterceptor {

    private static final System.Logger LOG = System.getLogger(InterceptorServidorFirma.class.getName());
    private static final String NO_AUTORIZADA = "Llamada no autorizada";

    private final FirmaHmac firma;

    public InterceptorServidorFirma(FirmaHmac firma) {
        this.firma = firma;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> llamada,
                                                                 Metadata cabeceras,
                                                                 ServerCallHandler<ReqT, RespT> siguiente) {
        Transporte transporte = Metadatos.VALOR_GRPC_WEB.equals(cabeceras.get(Metadatos.TRANSPORTE))
                ? Transporte.GRPC_WEB
                : Transporte.GRPC;
        Context contexto = Context.current().withValue(Transporte.CLAVE, transporte);
        String metodo = llamada.getMethodDescriptor().getFullMethodName();

        if (!llamada.getMethodDescriptor().getType().clientSendsOneMessage()) {
            if (!verificar(cabeceras, metodo, FirmaHmac.SIN_CUERPO)) {
                llamada.close(Status.UNAUTHENTICATED.withDescription(NO_AUTORIZADA), new Metadata());
                return new ServerCall.Listener<>() {
                };
            }
            return Contexts.interceptCall(contexto, llamada, cabeceras, siguiente);
        }

        ServerCall.Listener<ReqT> delegado = Contexts.interceptCall(contexto, llamada, cabeceras, siguiente);
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegado) {
            private boolean verificada;
            private boolean rechazada;

            @Override
            public void onMessage(ReqT mensaje) {
                if (rechazada) {
                    return;
                }
                if (!verificada) {
                    if (!(mensaje instanceof byte[] bytes)) {
                        throw new IllegalStateException(
                                "InterceptorServidorFirma debe aplicarse sobre useMarshalledMessages");
                    }
                    if (!verificar(cabeceras, metodo, bytes)) {
                        rechazada = true;
                        llamada.close(Status.UNAUTHENTICATED.withDescription(NO_AUTORIZADA), new Metadata());
                        return;
                    }
                    verificada = true;
                }
                super.onMessage(mensaje);
            }

            @Override
            public void onHalfClose() {
                if (!rechazada) {
                    super.onHalfClose();
                }
            }

            @Override
            public void onReady() {
                if (!rechazada) {
                    super.onReady();
                }
            }
        };
    }

    private boolean verificar(Metadata cabeceras, String metodo, byte[] cuerpo) {
        try {
            firma.verificar(cabeceras.get(Metadatos.TIMESTAMP), cabeceras.get(Metadatos.FIRMA), metodo, cuerpo);
            return true;
        } catch (FirmaInvalidaException e) {
            LOG.log(Level.WARNING, "Llamada rechazada por firma: {0} ({1})", metodo, e.motivo());
            return false;
        }
    }
}

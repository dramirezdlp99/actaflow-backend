package com.actaflow.backend.plataforma.grpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import com.actaflow.backend.plataforma.seguridad.FirmaHmac;

import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/**
 * Firma con HMAC cada llamada saliente (por ejemplo, de legal-case-service al motor de IA).
 *
 * <p>La firma viaja en las cabeceras pero cubre los bytes del mensaje, que solo se conocen cuando
 * el codigo llama a {@code sendMessage}. Por eso, en los metodos con un solo mensaje del cliente, el
 * inicio real de la llamada se difiere hasta ese momento. Funciona igual sobre un canal gRPC nativo y
 * sobre {@link com.actaflow.backend.plataforma.grpcweb.CanalGrpcWeb}.
 */
public final class InterceptorClienteFirma implements ClientInterceptor {

    private final FirmaHmac firma;

    public InterceptorClienteFirma(FirmaHmac firma) {
        this.firma = firma;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> metodo,
                                                               CallOptions opciones, Channel siguiente) {
        ClientCall<ReqT, RespT> llamada = siguiente.newCall(metodo, opciones);
        String nombre = metodo.getFullMethodName();
        if (!metodo.getType().clientSendsOneMessage()) {
            return new ForwardingClientCall.SimpleForwardingClientCall<>(llamada) {
                @Override
                public void start(Listener<RespT> oyente, Metadata cabeceras) {
                    cabeceras.merge(firma.metadatos(nombre, FirmaHmac.SIN_CUERPO));
                    super.start(oyente, cabeceras);
                }
            };
        }
        return new LlamadaFirmadaDiferida<>(llamada, metodo, firma);
    }

    /** Llamada cuyo inicio espera al unico mensaje, para poder firmarlo. */
    static final class LlamadaFirmadaDiferida<ReqT, RespT> extends ClientCall<ReqT, RespT> {

        private final ClientCall<ReqT, RespT> delegada;
        private final MethodDescriptor<ReqT, RespT> metodo;
        private final FirmaHmac firma;
        private Listener<RespT> oyente;
        private Metadata cabeceras;
        private int solicitudesPendientes;
        private Boolean compresion;
        private boolean iniciada;

        LlamadaFirmadaDiferida(ClientCall<ReqT, RespT> delegada, MethodDescriptor<ReqT, RespT> metodo,
                               FirmaHmac firma) {
            this.delegada = delegada;
            this.metodo = metodo;
            this.firma = firma;
        }

        @Override
        public void start(Listener<RespT> oyenteRespuestas, Metadata cabecerasIniciales) {
            this.oyente = oyenteRespuestas;
            this.cabeceras = cabecerasIniciales;
        }

        private void iniciar(byte[] cuerpo) {
            cabeceras.merge(firma.metadatos(metodo.getFullMethodName(), cuerpo));
            delegada.start(oyente, cabeceras);
            iniciada = true;
            if (compresion != null) {
                delegada.setMessageCompression(compresion);
            }
            if (solicitudesPendientes > 0) {
                delegada.request(solicitudesPendientes);
            }
        }

        @Override
        public void request(int cantidad) {
            if (iniciada) {
                delegada.request(cantidad);
            } else {
                solicitudesPendientes += cantidad;
            }
        }

        @Override
        public void sendMessage(ReqT mensaje) {
            if (!iniciada) {
                iniciar(bytesDe(mensaje));
            }
            delegada.sendMessage(mensaje);
        }

        @Override
        public void halfClose() {
            if (!iniciada) {
                iniciar(FirmaHmac.SIN_CUERPO);
            }
            delegada.halfClose();
        }

        @Override
        public void cancel(String mensaje, Throwable causa) {
            if (!iniciada && oyente != null) {
                // Sin iniciar, el oyente nunca recibiria onClose: se inicia para poder cancelar.
                iniciar(FirmaHmac.SIN_CUERPO);
            }
            delegada.cancel(mensaje, causa);
        }

        @Override
        public boolean isReady() {
            return iniciada && delegada.isReady();
        }

        @Override
        public void setMessageCompression(boolean habilitada) {
            if (iniciada) {
                delegada.setMessageCompression(habilitada);
            } else {
                compresion = habilitada;
            }
        }

        @Override
        public Attributes getAttributes() {
            return iniciada ? delegada.getAttributes() : Attributes.EMPTY;
        }

        private byte[] bytesDe(ReqT mensaje) {
            try (InputStream flujo = metodo.streamRequest(mensaje)) {
                return flujo.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo serializar el mensaje para firmarlo", e);
            }
        }
    }
}

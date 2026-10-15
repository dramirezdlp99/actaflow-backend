package com.actaflow.backend.identidad.grpc;

import java.time.Clock;
import java.util.Optional;

import com.actaflow.backend.identidad.dominio.FirmaTokens;
import com.actaflow.backend.identidad.dominio.Principal;
import com.actaflow.backend.plataforma.grpc.Metadatos;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * Lee el token de acceso ({@code authorization: Bearer ...}) y deja el principal en el contexto.
 * Sin token la llamada sigue (hay metodos publicos, como Login); con un token invalido o vencido
 * se rechaza, para que el cliente sepa que debe refrescar la sesion.
 */
public final class InterceptorSesion implements ServerInterceptor {

    private static final String PREFIJO = "Bearer ";

    private final FirmaTokens tokens;
    private final Clock reloj;

    public InterceptorSesion(FirmaTokens tokens, Clock reloj) {
        this.tokens = tokens;
        this.reloj = reloj;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> llamada, Metadata metadatos,
                                                       ServerCallHandler<Q, R> siguiente) {
        String autorizacion = metadatos.get(Metadatos.AUTORIZACION);
        if (autorizacion == null || autorizacion.isBlank()) {
            return siguiente.startCall(llamada, metadatos);
        }
        Optional<Principal> principal = autorizacion.startsWith(PREFIJO)
                ? tokens.verificar(autorizacion.substring(PREFIJO.length()).strip(), reloj.instant())
                : Optional.empty();
        if (principal.isEmpty()) {
            llamada.close(Status.UNAUTHENTICATED.withDescription("Sesion vencida o no valida"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        return Contexts.interceptCall(Context.current().withValue(Sesion.CLAVE, principal.get()), llamada,
                metadatos, siguiente);
    }
}

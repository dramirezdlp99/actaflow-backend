package com.actaflow.backend.expedientes.seguridad;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;

import com.actaflow.backend.plataforma.grpc.Metadatos;
import com.actaflow.backend.plataforma.seguridad.TokenAcceso;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * Exige un token de acceso valido ({@code authorization: Bearer ...}) y deja al usuario en el
 * contexto. Se verifica LOCALMENTE con la clave publica de identity: no hay una llamada a identity
 * por cada peticion.
 */
public final class InterceptorSesion implements ServerInterceptor {

    /** Usuario de la llamada en curso. */
    public static final Context.Key<Usuario> USUARIO = Context.key("actaflow-usuario");
    private static final String PREFIJO = "Bearer ";

    private final ClavesIdentidad claves;
    private final Clock reloj;

    public InterceptorSesion(ClavesIdentidad claves, Clock reloj) {
        this.claves = claves;
        this.reloj = reloj;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> llamada, Metadata metadatos,
                                                       ServerCallHandler<Q, R> siguiente) {
        Optional<Usuario> usuario = usuario(metadatos.get(Metadatos.AUTORIZACION));
        if (usuario.isEmpty()) {
            llamada.close(Status.UNAUTHENTICATED.withDescription("Sesion vencida o no valida"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        return Contexts.interceptCall(Context.current().withValue(USUARIO, usuario.get()), llamada, metadatos,
                siguiente);
    }

    Optional<Usuario> usuario(String autorizacion) {
        if (autorizacion == null || !autorizacion.startsWith(PREFIJO)) {
            return Optional.empty();
        }
        String token = autorizacion.substring(PREFIJO.length()).strip();
        Optional<String> idClave = TokenAcceso.idClaveDe(token);
        if (idClave.isEmpty()) {
            return Optional.empty();
        }
        return claves.clave(idClave.get())
                .flatMap(clave -> TokenAcceso.verificar(token, reloj.instant(), clave, idClave.get()))
                .map(r -> new Usuario(r.cuenta(), r.organizacion(), r.rol(),
                        r.correo().strip().toLowerCase(Locale.ROOT), r.nombre()));
    }

    /** Usuario autenticado de la llamada en curso (el interceptor garantiza que existe). */
    public static Usuario actual() {
        Usuario usuario = USUARIO.get();
        if (usuario == null) {
            throw new IllegalStateException("Servicio de expedientes publicado sin InterceptorSesion");
        }
        return usuario;
    }
}

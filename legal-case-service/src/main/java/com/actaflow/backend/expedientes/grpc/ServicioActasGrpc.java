package com.actaflow.backend.expedientes.grpc;

import static com.actaflow.backend.plataforma.rpc.Respuestas.responder;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.actaflow.backend.expedientes.aplicacion.ServicioActas;
import com.actaflow.backend.expedientes.seguridad.InterceptorSesion;
import com.actaflow.contracts.minutes.v1.AcceptFinalDocumentRequest;
import com.actaflow.contracts.minutes.v1.AcceptFinalDocumentResponse;
import com.actaflow.contracts.minutes.v1.CloseMinuteRequest;
import com.actaflow.contracts.minutes.v1.CloseMinuteResponse;
import com.actaflow.contracts.minutes.v1.CreateMinuteRequest;
import com.actaflow.contracts.minutes.v1.CreateMinuteResponse;
import com.actaflow.contracts.minutes.v1.GetFinalDocumentRequest;
import com.actaflow.contracts.minutes.v1.GetFinalDocumentResponse;
import com.actaflow.contracts.minutes.v1.GetMinuteRequest;
import com.actaflow.contracts.minutes.v1.GetMinuteResponse;
import com.actaflow.contracts.minutes.v1.ListMinutesRequest;
import com.actaflow.contracts.minutes.v1.ListMinutesResponse;
import com.actaflow.contracts.minutes.v1.MinuteServiceGrpc;
import com.actaflow.contracts.minutes.v1.VerifyDocumentRequest;
import com.actaflow.contracts.minutes.v1.VerifyDocumentResponse;

import io.grpc.stub.StreamObserver;

/**
 * MinuteService del contrato. FillMinute queda sin implementar (UNIMPLEMENTED): desde v0.4.0 el
 * navegador llena la plantilla (ADR 0004). VerifyDocument es publico: no exige sesion.
 */
public final class ServicioActasGrpc extends MinuteServiceGrpc.MinuteServiceImplBase {

    /** Metodos sin sesion (la firma del BFF sigue siendo obligatoria). */
    public static final Set<String> PUBLICOS = Set.of(MinuteServiceGrpc.getVerifyDocumentMethod().getFullMethodName());

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern TOTP = Pattern.compile("^[0-9]{6}$");

    private final ServicioActas servicio;

    public ServicioActasGrpc(ServicioActas servicio) {
        this.servicio = servicio;
    }

    @Override
    public void createMinute(CreateMinuteRequest p, StreamObserver<CreateMinuteResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("case_id", p.getCaseId()).uuid("template_id", p.getTemplateId())
                    .cumple("template_version", "int32.gte", p.getTemplateVersion() >= 1,
                            "value must be greater than or equal to 1")
                    .uuid("idempotency_key", p.getIdempotencyKey())
                    .verificar();
            return servicio.crear(InterceptorSesion.actual(), p);
        });
    }

    @Override
    public void getMinute(GetMinuteRequest p, StreamObserver<GetMinuteResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("minute_id", p.getMinuteId()).verificar();
            return servicio.obtener(InterceptorSesion.actual(), UUID.fromString(p.getMinuteId()));
        });
    }

    @Override
    public void listMinutes(ListMinutesRequest p, StreamObserver<ListMinutesResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("case_id", p.getCaseId()).verificar();
            return servicio.listar(InterceptorSesion.actual(), UUID.fromString(p.getCaseId()));
        });
    }

    @Override
    public void closeMinute(CloseMinuteRequest p, StreamObserver<CloseMinuteResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("minute_id", p.getMinuteId())
                    .sobre("final_content", p.hasFinalContent(), p.getFinalContent())
                    .uuid("idempotency_key", p.getIdempotencyKey())
                    .verificar();
            return servicio.cerrar(InterceptorSesion.actual(), p);
        });
    }

    @Override
    public void getFinalDocument(GetFinalDocumentRequest p, StreamObserver<GetFinalDocumentResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("minute_id", p.getMinuteId()).verificar();
            return servicio.documento(InterceptorSesion.actual(), UUID.fromString(p.getMinuteId()));
        });
    }

    @Override
    public void acceptFinalDocument(AcceptFinalDocumentRequest p, StreamObserver<AcceptFinalDocumentResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("document_id", p.getDocumentId())
                    .patron("sha256_hex", p.getSha256Hex(), SHA256)
                    .patron("totp_code", p.getTotpCode(), TOTP)
                    .largo("presence_token", p.getPresenceToken(), 1, 2048)
                    .verificar();
            return servicio.aceptar(InterceptorSesion.actual(), p);
        });
    }

    @Override
    public void verifyDocument(VerifyDocumentRequest p, StreamObserver<VerifyDocumentResponse> r) {
        responder(r, () -> {
            new Reglas().patron("sha256_hex", p.getSha256Hex(), SHA256).verificar();
            return servicio.verificar(p.getSha256Hex());
        });
    }
}

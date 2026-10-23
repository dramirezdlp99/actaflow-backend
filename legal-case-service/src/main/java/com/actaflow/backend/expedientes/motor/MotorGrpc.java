package com.actaflow.backend.expedientes.motor;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.actaflow.backend.expedientes.aplicacion.MotorIA;
import com.actaflow.backend.plataforma.grpc.ClienteGrpc;
import com.actaflow.contracts.crypto.v1.EncryptedBlob;
import com.actaflow.contracts.crypto.v1.SealedEnvelope;
import com.actaflow.contracts.engine.v1.DocumentServiceGrpc;
import com.actaflow.contracts.engine.v1.ExtractEntitiesRequest;
import com.actaflow.contracts.engine.v1.ExtractionServiceGrpc;
import com.actaflow.contracts.engine.v1.RenderMinutePdfRequest;
import com.actaflow.contracts.engine.v1.RenderMinutePdfResponse;
import com.actaflow.contracts.engine.v1.TranscribeSegmentRequest;
import com.actaflow.contracts.engine.v1.TranscriptionServiceGrpc;

/**
 * El motor por gRPC (firmado con HMAC). Plazo largo: en Render Free el motor puede estar despertando
 * y cargando el modelo, y una CPU transcribe 30 s de audio en unos 30 s.
 */
public final class MotorGrpc implements MotorIA {

    private final ClienteGrpc cliente;
    private final Duration plazo;

    public MotorGrpc(ClienteGrpc cliente, Duration plazo) {
        this.cliente = cliente;
        this.plazo = plazo;
    }

    @Override
    public EncryptedBlob transcribir(String casoId, String segmentoId, SealedEnvelope audio) {
        return TranscriptionServiceGrpc.newBlockingStub(cliente.canal())
                .withDeadlineAfter(plazo.toMillis(), TimeUnit.MILLISECONDS)
                .transcribeSegment(TranscribeSegmentRequest.newBuilder().setCaseId(casoId).setSegmentId(segmentoId)
                        .setAudio(audio).setLanguage("es-CO").build())
                .getTranscript();
    }

    @Override
    public EncryptedBlob extraer(String casoId, String extraccionId, SealedEnvelope entrada) {
        return ExtractionServiceGrpc.newBlockingStub(cliente.canal())
                .withDeadlineAfter(plazo.toMillis(), TimeUnit.MILLISECONDS)
                .extractEntities(ExtractEntitiesRequest.newBuilder().setCaseId(casoId).setExtractionId(extraccionId)
                        .setInput(entrada).build())
                .getEntities();
    }

    @Override
    public Renderizado renderizar(String casoId, String actaId, SealedEnvelope contenido) {
        RenderMinutePdfResponse respuesta = DocumentServiceGrpc.newBlockingStub(cliente.canal())
                .withDeadlineAfter(plazo.toMillis(), TimeUnit.MILLISECONDS)
                .renderMinutePdf(RenderMinutePdfRequest.newBuilder().setCaseId(casoId).setMinuteId(actaId)
                        .setFinalContent(contenido).build());
        return new Renderizado(respuesta.getPdf(), respuesta.getSha256Hex(), respuesta.getPageCount());
    }
}

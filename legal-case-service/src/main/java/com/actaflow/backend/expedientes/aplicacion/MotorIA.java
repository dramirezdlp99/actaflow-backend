package com.actaflow.backend.expedientes.aplicacion;

import com.actaflow.contracts.crypto.v1.EncryptedBlob;
import com.actaflow.contracts.crypto.v1.SealedEnvelope;

/**
 * Lo que legal-case le pide al motor de IA. Solo pasan sobres sellados y resultados cifrados: el
 * backend nunca ve el audio, la transcripcion, las entidades ni el acta.
 */
public interface MotorIA {

    /** TranscribeSegment: devuelve el TranscriptionResult cifrado con la clave del segmento. */
    EncryptedBlob transcribir(String casoId, String segmentoId, SealedEnvelope audio);

    /** ExtractEntities: devuelve el EntityExtractionResult cifrado con la clave de la extraccion. */
    EncryptedBlob extraer(String casoId, String extraccionId, SealedEnvelope entrada);

    /** PDF del acta (cifrado con la clave de contenido), su SHA-256 en hex y sus paginas. */
    record Renderizado(EncryptedBlob pdf, String sha256Hex, int paginas) {
    }

    /** RenderMinutePdf: el motor hace de procesador de documentos del acta final. */
    Renderizado renderizar(String casoId, String actaId, SealedEnvelope contenido);
}

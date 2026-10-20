package com.actaflow.backend.expedientes.grpc;

import static com.actaflow.backend.plataforma.rpc.Respuestas.responder;

import java.util.Set;
import java.util.UUID;

import com.actaflow.backend.expedientes.aplicacion.ServicioPlantillas;
import com.actaflow.backend.expedientes.seguridad.InterceptorSesion;
import com.actaflow.contracts.templates.v1.GetPublishedTemplateRequest;
import com.actaflow.contracts.templates.v1.GetPublishedTemplateResponse;
import com.actaflow.contracts.templates.v1.GetTemplateVersionRequest;
import com.actaflow.contracts.templates.v1.GetTemplateVersionResponse;
import com.actaflow.contracts.templates.v1.ListOrganizationTemplatesRequest;
import com.actaflow.contracts.templates.v1.ListOrganizationTemplatesResponse;
import com.actaflow.contracts.templates.v1.ListPublishedTemplatesRequest;
import com.actaflow.contracts.templates.v1.ListPublishedTemplatesResponse;
import com.actaflow.contracts.templates.v1.ListTemplateVersionsRequest;
import com.actaflow.contracts.templates.v1.ListTemplateVersionsResponse;
import com.actaflow.contracts.templates.v1.PreviewTemplateRequest;
import com.actaflow.contracts.templates.v1.PreviewTemplateResponse;
import com.actaflow.contracts.templates.v1.PublishTemplateRequest;
import com.actaflow.contracts.templates.v1.PublishTemplateResponse;
import com.actaflow.contracts.templates.v1.SaveTemplateDraftRequest;
import com.actaflow.contracts.templates.v1.SaveTemplateDraftResponse;
import com.actaflow.contracts.templates.v1.TemplateCategory;
import com.actaflow.contracts.templates.v1.TemplateServiceGrpc;
import com.actaflow.contracts.templates.v1.ValidateTemplateRequest;
import com.actaflow.contracts.templates.v1.ValidateTemplateResponse;

import io.grpc.stub.StreamObserver;

/** TemplateService del contrato: valida la peticion y delega en el caso de uso. */
public final class ServicioPlantillasGrpc extends TemplateServiceGrpc.TemplateServiceImplBase {

    /** Metodos que no exigen sesion (la biblioteca publicada); la firma del BFF sigue siendo obligatoria. */
    public static final Set<String> PUBLICOS = Set.of(
            TemplateServiceGrpc.getListPublishedTemplatesMethod().getFullMethodName(),
            TemplateServiceGrpc.getGetPublishedTemplateMethod().getFullMethodName());

    private static final int MAX_FUENTE = 200_000;

    private final ServicioPlantillas servicio;

    public ServicioPlantillasGrpc(ServicioPlantillas servicio) {
        this.servicio = servicio;
    }

    @Override
    public void listPublishedTemplates(ListPublishedTemplatesRequest p,
                                       StreamObserver<ListPublishedTemplatesResponse> r) {
        responder(r, () -> {
            new Reglas().pagina("page", p.hasPage(), p.getPage())
                    .definido("category", p.getCategory() != TemplateCategory.UNRECOGNIZED)
                    .verificar();
            return servicio.listarPublicadas(p.getPage().getPageSize(), p.getPage().getPageToken(), p.getCategory());
        });
    }

    @Override
    public void getPublishedTemplate(GetPublishedTemplateRequest p, StreamObserver<GetPublishedTemplateResponse> r) {
        responder(r, () -> {
            new Reglas().slug("slug", p.getSlug()).verificar();
            return servicio.publicada(p.getSlug());
        });
    }

    @Override
    public void validateTemplate(ValidateTemplateRequest p, StreamObserver<ValidateTemplateResponse> r) {
        responder(r, () -> {
            new Reglas().largo("source", p.getSource(), 1, MAX_FUENTE).verificar();
            return servicio.validar(InterceptorSesion.actual(), p.getSource());
        });
    }

    @Override
    public void previewTemplate(PreviewTemplateRequest p, StreamObserver<PreviewTemplateResponse> r) {
        responder(r, () -> {
            new Reglas().largo("source", p.getSource(), 1, MAX_FUENTE).valores("values", p.getValuesMap()).verificar();
            return servicio.previsualizar(InterceptorSesion.actual(), p.getSource(), p.getValuesMap());
        });
    }

    @Override
    public void saveTemplateDraft(SaveTemplateDraftRequest p, StreamObserver<SaveTemplateDraftResponse> r) {
        responder(r, () -> {
            Reglas reglas = new Reglas();
            if (!p.getTemplateId().isEmpty()) {
                reglas.uuid("template_id", p.getTemplateId());
            }
            reglas.slug("slug", p.getSlug())
                    .largo("title", p.getTitle(), 3, 160)
                    .largo("description", p.getDescription(), 0, 500)
                    .elegido("category", p.getCategory(), p.getCategory() != TemplateCategory.UNRECOGNIZED)
                    .largo("source", p.getSource(), 1, MAX_FUENTE)
                    .verificar();
            return servicio.guardarBorrador(InterceptorSesion.actual(), p);
        });
    }

    @Override
    public void publishTemplate(PublishTemplateRequest p, StreamObserver<PublishTemplateResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("template_id", p.getTemplateId())
                    .cumple("version", "int32.gte", p.getVersion() >= 1, "value must be greater than or equal to 1")
                    .verificar();
            return servicio.publicar(InterceptorSesion.actual(), UUID.fromString(p.getTemplateId()), p.getVersion());
        });
    }

    @Override
    public void listTemplateVersions(ListTemplateVersionsRequest p, StreamObserver<ListTemplateVersionsResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("template_id", p.getTemplateId()).verificar();
            return servicio.versiones(InterceptorSesion.actual(), UUID.fromString(p.getTemplateId()));
        });
    }

    @Override
    public void listOrganizationTemplates(ListOrganizationTemplatesRequest p,
                                          StreamObserver<ListOrganizationTemplatesResponse> r) {
        responder(r, () -> {
            new Reglas().pagina("page", p.hasPage(), p.getPage()).verificar();
            return servicio.deMiOrganizacion(InterceptorSesion.actual(), p.getPage().getPageSize(),
                    p.getPage().getPageToken());
        });
    }

    @Override
    public void getTemplateVersion(GetTemplateVersionRequest p, StreamObserver<GetTemplateVersionResponse> r) {
        responder(r, () -> {
            new Reglas().uuid("template_id", p.getTemplateId())
                    .cumple("version", "int32.gte", p.getVersion() >= 1, "value must be greater than or equal to 1")
                    .verificar();
            return servicio.version(InterceptorSesion.actual(), UUID.fromString(p.getTemplateId()), p.getVersion());
        });
    }
}

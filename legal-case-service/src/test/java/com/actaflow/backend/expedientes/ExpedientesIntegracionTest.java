package com.actaflow.backend.expedientes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import com.actaflow.backend.expedientes.soporte.ClienteBff;
import com.actaflow.backend.expedientes.soporte.IdentidadFalsa;
import com.actaflow.backend.plataforma.grpc.ClienteGrpc;
import com.actaflow.backend.plataforma.grpc.Metadatos;
import com.actaflow.backend.plataforma.grpc.ServidorGrpc;
import com.actaflow.backend.plataforma.seguridad.FirmaHmac;
import com.actaflow.contracts.cases.v1.CaseKind;
import com.actaflow.contracts.cases.v1.CaseRole;
import com.actaflow.contracts.cases.v1.CaseServiceGrpc;
import com.actaflow.contracts.cases.v1.CreateCaseRequest;
import com.actaflow.contracts.cases.v1.ListCasesRequest;
import com.actaflow.contracts.common.v1.PageRequest;
import com.actaflow.contracts.crypto.v1.CipherSuite;
import com.actaflow.contracts.crypto.v1.EncryptedBlob;
import com.actaflow.contracts.crypto.v1.WrappedKey;
import com.actaflow.contracts.templates.v1.ListPublishedTemplatesRequest;
import com.actaflow.contracts.templates.v1.TemplateServiceGrpc;
import com.actaflow.contracts.templates.v1.ValidateTemplateRequest;
import com.google.protobuf.ByteString;

import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Arranque completo de expedientes: el BFF llama por gRPC-Web y por gRPC nativo con su firma HMAC y
 * el token de la persona; el servicio pide la clave publica a una identidad falsa y guarda en
 * PostgreSQL real (migraciones de Flyway, con las plantillas del sistema de V4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ExpedientesIntegracionTest {

    private static final IdentidadFalsa IDENTIDAD = IdentidadFalsa.iniciar();
    private static final UUID ORG = UUID.randomUUID();

    @Value("${local.server.port}")
    private int puerto;

    @Autowired
    private ServidorGrpc servidorGrpc;

    @DynamicPropertySource
    static void apuntarALaIdentidadFalsa(DynamicPropertyRegistry registro) {
        registro.add("actaflow.identidad.transport", () -> "grpc");
        registro.add("actaflow.identidad.grpc-target", IDENTIDAD::destinoNativo);
    }

    @AfterAll
    static void apagar() {
        IDENTIDAD.close();
    }

    private ClienteGrpc bff(String transporte) {
        return ClienteGrpc.crear(transporte, "127.0.0.1:" + servidorGrpc.puertoNativo(),
                URI.create("http://127.0.0.1:" + puerto), new FirmaHmac(ClienteBff.SECRETO, 300, Clock.systemUTC()),
                Duration.ofSeconds(2), Duration.ofSeconds(20), 1 << 20);
    }

    private static Channel como(Channel canal, String token) {
        Metadata cabeceras = new Metadata();
        cabeceras.put(Metadatos.AUTORIZACION, "Bearer " + token);
        return ClientInterceptors.intercept(canal, MetadataUtils.newAttachHeadersInterceptor(cabeceras));
    }

    private static CreateCaseRequest nuevo(String radicado) {
        byte[] efimera = new byte[65];
        efimera[0] = 0x04;
        return CreateCaseRequest.newBuilder().setCaseNumber(radicado).setKind(CaseKind.CASE_KIND_MEDIATION)
                .setDetails(EncryptedBlob.newBuilder().setSuite(CipherSuite.CIPHER_SUITE_AES_256_GCM)
                        .setKeyId("case:v1").setNonce(ByteString.copyFrom(new byte[12]))
                        .setCiphertext(ByteString.copyFrom(new byte[40])))
                .setWrappedCaseKey(WrappedKey.newBuilder()
                        .setSuite(CipherSuite.CIPHER_SUITE_ECDH_P256_HKDF_SHA256_AES_256_GCM)
                        .setRecipientKeyId("cuenta:x:v1").setEphemeralPublicKey(ByteString.copyFrom(efimera))
                        .setNonce(ByteString.copyFrom(new byte[12])).setWrappedKey(ByteString.copyFrom(new byte[48])))
                .setIdempotencyKey(UUID.randomUUID().toString()).build();
    }

    @Test
    void porAmbosTransportesLaPersonaCreaYVeSusExpedientes() {
        UUID cuenta = UUID.randomUUID();
        String token = IDENTIDAD.token(cuenta, ORG, "MEMBER", "ana@prueba.test", "Ana");
        try (ClienteGrpc web = bff("grpc-web"); ClienteGrpc nativo = bff("grpc")) {
            var creado = CaseServiceGrpc.newBlockingStub(como(web.canal(), token))
                    .createCase(nuevo("MED-" + cuenta.toString().substring(0, 8).toUpperCase()));
            assertEquals(CaseRole.CASE_ROLE_CONCILIATOR, creado.getMembership().getRole());
            var lista = CaseServiceGrpc.newBlockingStub(como(nativo.canal(), token)).listCases(
                    ListCasesRequest.newBuilder().setPage(PageRequest.newBuilder().setPageSize(10)).build());
            assertEquals(1, lista.getCasesCount());
            assertEquals(creado.getLegalCase().getId(), lista.getCases(0).getLegalCase().getId());
        }
    }

    @Test
    void sinTokenOConUnTokenAjenoNoHayExpedientes() {
        ListCasesRequest listar = ListCasesRequest.newBuilder()
                .setPage(PageRequest.newBuilder().setPageSize(10)).build();
        try (ClienteGrpc web = bff("grpc-web")) {
            StatusRuntimeException sinToken = assertThrows(StatusRuntimeException.class,
                    () -> CaseServiceGrpc.newBlockingStub(web.canal()).listCases(listar));
            assertEquals(Status.Code.UNAUTHENTICATED, sinToken.getStatus().getCode());
            StatusRuntimeException forjado = assertThrows(StatusRuntimeException.class,
                    () -> CaseServiceGrpc.newBlockingStub(como(web.canal(), "eyJhbGciOiJub25lIn0.e30."))
                            .listCases(listar));
            assertEquals(Status.Code.UNAUTHENTICATED, forjado.getStatus().getCode());
        }
    }

    @Test
    void laBibliotecaDePlantillasSeLeeSinSesionPorAmbosTransportes() {
        ListPublishedTemplatesRequest listar = ListPublishedTemplatesRequest.newBuilder()
                .setPage(PageRequest.newBuilder().setPageSize(10)).build();
        try (ClienteGrpc web = bff("grpc-web"); ClienteGrpc nativo = bff("grpc")) {
            assertEquals(3, TemplateServiceGrpc.newBlockingStub(web.canal()).listPublishedTemplates(listar)
                    .getTemplatesCount());
            assertEquals(3, TemplateServiceGrpc.newBlockingStub(nativo.canal()).listPublishedTemplates(listar)
                    .getTemplatesCount());
            StatusRuntimeException sinToken = assertThrows(StatusRuntimeException.class,
                    () -> TemplateServiceGrpc.newBlockingStub(web.canal()).validateTemplate(
                            ValidateTemplateRequest.newBuilder().setSource("x").build()));
            assertEquals(Status.Code.UNAUTHENTICATED, sinToken.getStatus().getCode());
        }
    }
}

-- ActaFlow legal-case-service - V6: actas, su documento final sellado y las aceptaciones.
-- Sin nombres de esquema: Flyway y el pool trabajan dentro de legal_case.
--
-- El acta se llena en el navegador (contratos v0.4.0, ADR 0004). Aqui solo viven: la plantilla y
-- la version que fijo, el contenido final SELLADO para el motor (se borra en cuanto hay PDF), el
-- PDF cifrado con su clave de contenido, el sello (SHA-256 + firma CMS) y quien lo acepto.

create table acta (
    id                uuid primary key,
    caso_id           uuid        not null references caso (id) on delete cascade,
    plantilla_id      uuid        not null,
    plantilla_version integer     not null,
    state             varchar(24) not null,
    created_by        uuid        not null,
    created_at        timestamptz not null,
    closed_at         timestamptz,
    contenido_final   bytea,
    ultimo_error      varchar(300),
    constraint ck_acta_estado check (state in ('DRAFT', 'SEALING', 'AWAITING_ACCEPTANCE', 'FINAL')),
    constraint ck_acta_cierre check ((state = 'DRAFT') = (closed_at is null)),
    constraint ck_acta_version check (plantilla_version >= 1)
);

create index ix_acta_caso on acta (caso_id, created_at desc);

comment on column acta.contenido_final is 'SealedEnvelope para el motor; se borra al generar el PDF';

create table documento_final (
    id                 uuid primary key,
    acta_id            uuid         not null references acta (id) on delete cascade,
    pdf                bytea        not null,
    clave_para_caso    bytea        not null,
    sha256             char(64)     not null,
    cms                bytea        not null,
    firmante           varchar(300) not null,
    huella_certificado char(64)     not null,
    paginas            integer      not null,
    sealed_at          timestamptz  not null,
    constraint uq_documento_acta unique (acta_id),
    constraint uq_documento_sha256 unique (sha256),
    constraint ck_documento_sha256 check (sha256 ~ '^[0-9a-f]{64}$')
);

-- Las partes del expediente al momento de sellar: cada una debe aceptar (accepted_at).
create table aceptacion (
    documento_id    uuid         not null references documento_final (id) on delete cascade,
    participante_id uuid         not null,
    nombre          varchar(200) not null,
    accepted_at     timestamptz,
    primary key (documento_id, participante_id)
);

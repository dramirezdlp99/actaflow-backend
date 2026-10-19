-- ActaFlow legal-case-service - V3: expedientes, participantes, linea de tiempo e idempotencia.
-- Sin nombres de esquema: Flyway y el pool trabajan dentro de legal_case.
--
-- Cifrado de punta a punta: el titulo y el resumen del caso viajan cifrados con la clave del caso
-- (columna detalles), y esa clave solo existe envuelta para la clave publica de cada participante
-- (columna clave_envuelta). El backend guarda los mensajes protobuf tal cual y no puede abrirlos.

create table caso (
    id                   uuid primary key,
    organization_id      uuid         not null,
    case_number          varchar(40)  not null,
    kind                 varchar(20)  not null,
    status               varchar(20)  not null,
    detalles             bytea        not null,
    scheduled_hearing_at timestamptz,
    revision             bigint       not null default 1,
    created_by           uuid         not null,
    created_at           timestamptz  not null,
    last_activity_at     timestamptz  not null,
    constraint uq_caso_numero unique (organization_id, case_number),
    constraint ck_caso_kind check (kind in ('CONCILIATION', 'MEDIATION', 'CONTRACT')),
    constraint ck_caso_status
        check (status in ('OPEN', 'IN_HEARING', 'AGREEMENT', 'NO_AGREEMENT', 'ARCHIVED')),
    constraint ck_caso_revision check (revision >= 1)
);

comment on table caso is 'Expediente: metadatos en claro, titulo y resumen cifrados en el navegador';
comment on column caso.detalles is 'EncryptedBlob (CaseDetails) serializado; el backend no tiene la clave';

-- Estado del participante, derivado: sin cuenta = INVITED, sin clave = AWAITING_KEY, si no ACTIVE.
create table participante (
    id             uuid primary key,
    caso_id        uuid         not null references caso (id) on delete cascade,
    account_id     uuid,
    email          varchar(254) not null,
    display_name   varchar(120) not null,
    role           varchar(20)  not null,
    side           varchar(20),
    capacity       varchar(20),
    clave_envuelta bytea,
    invited_at     timestamptz  not null,
    joined_at      timestamptz,
    constraint ck_participante_role check (role in ('CONCILIATOR', 'ASSISTANT', 'PARTY')),
    constraint ck_participante_side check (side in ('CLAIMANT', 'RESPONDENT')),
    constraint ck_participante_capacity check (capacity in ('PRINCIPAL', 'ATTORNEY')),
    -- Las partes llevan lado y calidad; los demas roles no.
    constraint ck_participante_parte
        check ((role = 'PARTY') = (side is not null and capacity is not null)
               and (side is null) = (capacity is null))
);

comment on column participante.clave_envuelta is 'WrappedKey serializado: la clave del caso para su clave publica';

create unique index uq_participante_correo on participante (caso_id, lower(email));
create unique index uq_participante_cuenta on participante (caso_id, account_id) where account_id is not null;
create index ix_participante_cuenta on participante (account_id) where account_id is not null;
-- Invitaciones pendientes: se vinculan por correo cuando la persona entra con su cuenta.
create index ix_participante_pendiente on participante (lower(email)) where account_id is null;

create table evento_caso (
    id                   uuid primary key,
    caso_id              uuid         not null references caso (id) on delete cascade,
    kind                 varchar(30)  not null,
    occurred_at          timestamptz  not null,
    actor_participant_id uuid,
    actor_display_name   varchar(120) not null default '',
    constraint ck_evento_kind check (kind in ('CREATED', 'PARTICIPANT_ADDED', 'HEARING_STARTED',
        'HEARING_ENDED', 'MINUTE_CREATED', 'DOCUMENT_SEALED', 'DOCUMENT_ACCEPTED', 'STATUS_CHANGED',
        'ATTACHMENT_ADDED'))
);

create index ix_evento_caso on evento_caso (caso_id, occurred_at desc, id desc);

-- Escrituras idempotentes: repetir una llamada con la misma clave devuelve la misma respuesta
-- (la bandeja de salida sin conexion puede reenviar). La clave es de la cuenta que la uso.
create table idempotencia (
    account_id uuid        not null,
    clave      uuid        not null,
    operacion  varchar(40) not null,
    respuesta  bytea       not null,
    created_at timestamptz not null,
    primary key (account_id, clave)
);

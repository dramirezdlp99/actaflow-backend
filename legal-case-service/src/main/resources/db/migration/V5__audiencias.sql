-- ActaFlow legal-case-service - V5: audiencias, segmentos de audio, extracciones y sus eventos.
-- Sin nombres de esquema: Flyway y el pool trabajan dentro de legal_case.
--
-- Todo el contenido va sellado o cifrado en el navegador: el audio, sellado para el motor de IA
-- (SealedEnvelope), y la transcripcion y las entidades, cifradas con la clave de contenido de cada
-- segmento o extraccion. El backend guarda y reenvia bytes que no puede abrir. El audio sellado se
-- borra en cuanto el motor lo transcribe.

create table audiencia (
    id         uuid primary key,
    caso_id    uuid        not null references caso (id) on delete cascade,
    state      varchar(12) not null,
    started_by uuid        not null,
    started_at timestamptz not null,
    ended_at   timestamptz,
    constraint ck_audiencia_estado check (state in ('IN_PROGRESS', 'ENDED')),
    constraint ck_audiencia_fin check ((state = 'ENDED') = (ended_at is not null))
);

-- A lo sumo una audiencia en curso por expediente.
create unique index uq_audiencia_en_curso on audiencia (caso_id) where state = 'IN_PROGRESS';

create table segmento_audio (
    id              uuid primary key,
    audiencia_id    uuid        not null references audiencia (id) on delete cascade,
    sequence        integer     not null,
    speaker_id      uuid        not null,
    duration_ms     bigint      not null,
    state           varchar(12) not null,
    audio           bytea,
    clave_para_caso bytea       not null,
    transcripcion   bytea,
    received_at     timestamptz not null,
    constraint uq_segmento_orden unique (audiencia_id, sequence),
    constraint ck_segmento_orden check (sequence >= 0),
    constraint ck_segmento_duracion check (duration_ms between 1 and 30000),
    constraint ck_segmento_estado check (state in ('QUEUED', 'PROCESSING', 'DONE', 'FAILED'))
);

comment on column segmento_audio.audio is 'SealedEnvelope para el motor; se borra al transcribir';

create table extraccion (
    id              uuid primary key,
    audiencia_id    uuid        not null references audiencia (id) on delete cascade,
    state           varchar(12) not null,
    entrada         bytea,
    clave_para_caso bytea       not null,
    propuesta       bytea,
    revisada        bytea,
    revision        bigint      not null default 0,
    revisada_por    uuid,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    constraint ck_extraccion_estado check (state in ('QUEUED', 'PROCESSING', 'DONE', 'FAILED')),
    constraint ck_extraccion_revision check (revision >= 0)
);

create index ix_extraccion_audiencia on extraccion (audiencia_id, created_at);

-- Lo que ve la sala en vivo (WatchHearingEvents): cada cambio de un segmento o una extraccion y el
-- fin de la audiencia, en orden. El evento solo apunta al recurso; el mensaje se arma al enviarlo.
create table evento_audiencia (
    secuencia    bigserial primary key,
    audiencia_id uuid        not null references audiencia (id) on delete cascade,
    tipo         varchar(12) not null,
    recurso_id   uuid        not null,
    creado_en    timestamptz not null,
    constraint ck_evento_audiencia_tipo check (tipo in ('SEGMENT', 'EXTRACTION', 'ENDED'))
);

create index ix_evento_audiencia on evento_audiencia (audiencia_id, secuencia);

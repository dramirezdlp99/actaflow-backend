-- ActaFlow legal-case-service - V1: cola de trabajos en el esquema propio legal_case.
-- Flyway crea el esquema y guarda su historial en legal_case.flyway_schema_history.
-- Cola de trabajos asincronos (transcripcion, extraccion, PDF y sello). Los workers toman
-- trabajos con FOR UPDATE SKIP LOCKED, asi varios hilos nunca procesan el mismo trabajo.
-- La carga util viaja cifrada por el cliente (procesador sellado): el backend no la puede leer.

comment on schema legal_case is 'ActaFlow: expedientes (casos, audiencias, plantillas, actas y trabajos)';

create table trabajo (
    id                 uuid primary key default gen_random_uuid(),
    tipo               varchar(40)  not null,
    estado             varchar(20)  not null default 'PENDIENTE',
    clave_idempotencia varchar(100) not null,
    carga_cifrada      bytea,
    intentos           integer      not null default 0,
    max_intentos       integer      not null default 5,
    disponible_en      timestamptz  not null default now(),
    bloqueado_hasta    timestamptz,
    ultimo_error       varchar(500),
    creado_en          timestamptz  not null default now(),
    actualizado_en     timestamptz  not null default now(),
    constraint ck_trabajo_estado
        check (estado in ('PENDIENTE', 'EN_PROCESO', 'COMPLETADO', 'FALLIDO')),
    constraint ck_trabajo_intentos
        check (intentos >= 0 and max_intentos between 1 and 20),
    constraint uq_trabajo_idempotencia unique (tipo, clave_idempotencia)
);

comment on table trabajo is 'Cola de trabajos asincronos de ActaFlow';
comment on column trabajo.carga_cifrada is 'Carga cifrada en el navegador; el backend no tiene la clave';

-- Indice parcial: solo los trabajos que un worker puede tomar.
create index ix_trabajo_disponibles
    on trabajo (disponible_en)
    where estado = 'PENDIENTE';

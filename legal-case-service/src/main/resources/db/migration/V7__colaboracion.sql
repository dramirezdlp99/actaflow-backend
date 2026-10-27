-- ActaFlow legal-case-service - V7: borrador colaborativo del acta (CollaborationService).
-- Sin nombres de esquema: Flyway y el pool trabajan dentro de legal_case.
--
-- El borrador lo editan el conciliador y los asistentes desde varios navegadores, incluso sin
-- conexion. Cada cambio llega CIFRADO con la clave del caso (EncryptedUpdate): el servidor no lo
-- lee, solo le da un numero de orden (secuencia) que todos aplican igual. update_id lo genera el
-- navegador: reenviar el mismo cambio al volver la red no lo duplica.

alter table acta
    add column ultima_secuencia      bigint not null default 0,
    add column instantanea           bytea,
    add column instantanea_secuencia bigint not null default 0;

create table actualizacion_acta (
    acta_id     uuid        not null references acta (id) on delete cascade,
    secuencia   bigint      not null,
    update_id   uuid        not null,
    contenido   bytea,
    autor_id    uuid        not null,
    received_at timestamptz not null,
    primary key (acta_id, secuencia),
    constraint uq_actualizacion_acta unique (acta_id, update_id),
    constraint ck_actualizacion_secuencia check (secuencia >= 1)
);

comment on column actualizacion_acta.contenido is 'EncryptedBlob con la clave del caso; el servidor no lo abre. Al compactar queda en null: la fila se conserva para reconocer reenvios (update_id)';

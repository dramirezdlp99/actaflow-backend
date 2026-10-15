-- ActaFlow identity-service - V2: organizaciones, cuentas, invitaciones y sesiones.
--
-- Conocimiento cero: el servidor nunca recibe la contrasena. El navegador deriva con PBKDF2 un
-- verificador (para autenticarse) y, por separado, la clave que cifra su clave privada. Aqui solo
-- se guarda HMAC(pepper, verificador) y la clave privada YA cifrada en el navegador.

create table organization (
    id          uuid primary key,
    name        varchar(160) not null,
    tax_id      varchar(40)  not null default '',
    city        varchar(80)  not null default '',
    created_at  timestamptz  not null default now()
);

create table account (
    id                       uuid primary key,
    organization_id          uuid         not null references organization (id),
    email                    varchar(254) not null,
    display_name             varchar(120) not null,
    role                     varchar(20)  not null,
    active                   boolean      not null default true,
    kdf_salt                 bytea        not null,
    kdf_iterations           integer      not null,
    verifier_hash            bytea        not null,
    totp_secret_enc          bytea        not null,
    totp_enrollment_id       uuid         not null,
    totp_confirmed_at        timestamptz,
    totp_last_step           bigint       not null default 0,
    public_key_id            varchar(120) not null,
    public_key_algorithm     varchar(40)  not null,
    public_key               bytea        not null,
    public_key_fingerprint   varchar(64)  not null,
    private_key_by_password  bytea        not null,
    private_key_by_recovery  bytea        not null,
    registration_key         uuid         not null,
    failed_logins            integer      not null default 0,
    locked_until             timestamptz,
    last_sign_in_at          timestamptz,
    created_at               timestamptz  not null default now(),
    constraint ck_account_role check (role in ('ADMIN', 'MEMBER', 'EXTERNAL')),
    constraint ck_account_kdf check (kdf_iterations >= 600000 and octet_length(kdf_salt) = 16),
    constraint uq_account_enrollment unique (totp_enrollment_id),
    constraint uq_account_registration unique (registration_key)
);

create unique index ux_account_email on account (lower(email));
create index ix_account_organization on account (organization_id, created_at, id);

create table invitation (
    id               uuid primary key,
    organization_id  uuid         not null references organization (id),
    email            varchar(254) not null,
    role             varchar(20)  not null,
    token_hash       bytea        not null,
    expires_at       timestamptz  not null,
    used_at          timestamptz,
    created_by       uuid references account (id),
    idempotency_key  uuid,
    created_at       timestamptz  not null default now(),
    constraint ck_invitation_role check (role in ('ADMIN', 'MEMBER', 'EXTERNAL')),
    constraint uq_invitation_token unique (token_hash),
    constraint uq_invitation_idempotency unique (organization_id, idempotency_key)
);

-- Primer factor superado: el desafio espera el codigo TOTP o de recuperacion.
create table login_challenge (
    id          uuid primary key,
    account_id  uuid        not null references account (id),
    expires_at  timestamptz not null,
    used_at     timestamptz,
    attempts    integer     not null default 0
);

-- Tokens de refresco rotativos: cada uso emite uno nuevo de la misma familia. Reusar uno ya
-- rotado revela un robo y revoca toda la familia.
create table refresh_token (
    id          uuid primary key,
    account_id  uuid        not null references account (id),
    family_id   uuid        not null,
    token_hash  bytea       not null,
    expires_at  timestamptz not null,
    revoked_at  timestamptz,
    created_at  timestamptz not null default now(),
    constraint uq_refresh_token unique (token_hash)
);

create index ix_refresh_family on refresh_token (family_id);

create table recovery_code (
    account_id  uuid  not null references account (id),
    code_hash   bytea not null,
    used_at     timestamptz,
    primary key (account_id, code_hash)
);

-- ActaFlow legal-case-service - V4: plantillas juridicas y sus versiones.
-- Sin nombres de esquema: Flyway y el pool trabajan dentro de legal_case.
--
-- Las plantillas no llevan datos de casos, asi que van en claro y la biblioteca publicada es publica.
-- organization_id nulo = plantilla del sistema (de ActaFlow): todos la ven y nadie la edita.

create table plantilla (
    id              uuid primary key,
    slug            varchar(120) not null,
    organization_id uuid,
    created_by      uuid,
    created_at      timestamptz  not null,
    constraint uq_plantilla_slug unique (slug),
    constraint ck_plantilla_slug check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

comment on table plantilla is 'Plantilla juridica; su contenido vive en plantilla_version';

-- Una version es BORRADOR (editable), PUBLICADA (inmutable, a lo sumo una por plantilla) o
-- REEMPLAZADA (la publico una version mas nueva).
create table plantilla_version (
    plantilla_id uuid         not null references plantilla (id) on delete cascade,
    version      integer      not null,
    state        varchar(12)  not null,
    title        varchar(160) not null,
    description  varchar(500) not null default '',
    category     varchar(30)  not null,
    source       text         not null,
    updated_by   uuid,
    updated_at   timestamptz  not null,
    published_at timestamptz,
    primary key (plantilla_id, version),
    constraint ck_version_numero check (version >= 1),
    constraint ck_version_estado check (state in ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    constraint ck_version_categoria check (category in ('CONCILIATION_AGREEMENT', 'NO_AGREEMENT_RECORD',
        'MEDIATION_MINUTE', 'CONTRACT')),
    constraint ck_version_publicada check ((state = 'DRAFT') = (published_at is null)),
    constraint ck_version_fuente check (length(source) between 1 and 200000)
);

create unique index uq_version_publicada on plantilla_version (plantilla_id) where state = 'PUBLISHED';
create index ix_plantilla_organizacion on plantilla (organization_id);

-- Plantillas del sistema, publicadas desde el inicio para que la biblioteca no arranque vacia.
insert into plantilla (id, slug, organization_id, created_by, created_at) values
    ('00000000-0000-4000-8000-0000000000a1', 'acta-conciliacion-arrendamiento', null, null, '2026-10-20T00:00:00Z'),
    ('00000000-0000-4000-8000-0000000000a2', 'constancia-de-no-acuerdo', null, null, '2026-10-20T00:00:00Z'),
    ('00000000-0000-4000-8000-0000000000a3', 'acta-de-mediacion', null, null, '2026-10-20T00:00:00Z');

insert into plantilla_version (plantilla_id, version, state, title, description, category, source, updated_by,
                               updated_at, published_at) values
('00000000-0000-4000-8000-0000000000a1', 1, 'PUBLISHED', 'Acta de conciliación con acuerdo: arrendamiento',
 'Acuerdo de pago de cánones adeudados y restitución del inmueble, con pago único o por cuotas.',
 'CONCILIATION_AGREEMENT', $fuente$# ACTA DE CONCILIACIÓN No. {{ caso.radicado }}

En la ciudad de {{ caso.ciudad }}, el {{ audiencia.fecha | fecha_en_letras }}, ante **{{ conciliador.nombre }}**, conciliador(a) en derecho, comparecieron:

**Convocante:** {{ partes.convocante.nombre }}, identificado(a) con {{ partes.convocante.documento }}.
**Convocado:** {{ partes.convocado.nombre }}, identificado(a) con {{ partes.convocado.documento }}.
{% si partes.convocante.apoderado %}
**Apoderado(a) del convocante:** {{ partes.convocante.apoderado }}.
{% fin %}

## Hechos

Las partes celebraron un contrato de arrendamiento sobre el inmueble ubicado en {{ inmueble.direccion }}. El convocante manifiesta que el convocado adeuda cánones de arrendamiento por {{ acuerdo.monto | moneda_en_letras }}.

## Acuerdo

Las partes acuerdan, de manera libre y voluntaria, lo siguiente:

{% si acuerdo.cuotas > 0 %}
PRIMERO. El convocado pagará {{ acuerdo.monto | moneda_en_letras }} en {{ acuerdo.cuotas | numero_en_letras }} cuotas, así:

{% para cada cuota en acuerdo.plan %}
Cuota de {{ cuota.valor | moneda }} con vencimiento el {{ cuota.fecha | fecha }}.
{% fin %}
{% sino %}
PRIMERO. El convocado pagará {{ acuerdo.monto | moneda_en_letras }} en un solo pago, a más tardar el {{ acuerdo.fecha_pago | fecha }}.
{% fin %}

SEGUNDO. El convocado restituirá el inmueble a más tardar el {{ acuerdo.fecha_restitucion | fecha }}.

{% si acuerdo.clausula_penal %}
TERCERO. Si el convocado incumple, pagará a título de cláusula penal {{ acuerdo.clausula_penal | moneda_en_letras }}.
{% fin %}

Este acuerdo hace tránsito a cosa juzgada y presta mérito ejecutivo, conforme a la Ley 2220 de 2022.

Para constancia, firman quienes intervinieron.$fuente$, null, '2026-10-20T00:00:00Z', '2026-10-20T00:00:00Z'),
('00000000-0000-4000-8000-0000000000a2', 1, 'PUBLISHED', 'Constancia de no acuerdo',
 'Constancia de que la audiencia se celebró sin acuerdo, o de que una parte no asistió.',
 'NO_AGREEMENT_RECORD', $fuente$# CONSTANCIA DE NO ACUERDO No. {{ caso.radicado }}

El suscrito conciliador, **{{ conciliador.nombre }}**, hace constar que el {{ audiencia.fecha | fecha_en_letras }} se celebró en {{ caso.ciudad }} la audiencia de conciliación solicitada por {{ partes.convocante.nombre }} frente a {{ partes.convocado.nombre }}.

{% si audiencia.inasistencia %}
La parte {{ audiencia.parte_inasistente }} no asistió a la audiencia{% si audiencia.justifico %} y presentó justificación dentro del término legal{% sino %} y no presentó justificación{% fin %}.
{% sino %}
Ambas partes asistieron y expusieron sus pretensiones, pero no fue posible llegar a un acuerdo.
{% fin %}

Las pretensiones de la solicitud ascienden a {{ pretensiones.cuantia | moneda_en_letras }}.

Se expide la presente constancia a solicitud de parte.$fuente$, null, '2026-10-20T00:00:00Z', '2026-10-20T00:00:00Z'),
('00000000-0000-4000-8000-0000000000a3', 1, 'PUBLISHED', 'Acta de mediación',
 'Registro de participantes, situación y compromisos de una mediación, con seguimiento opcional.',
 'MEDIATION_MINUTE', $fuente$# ACTA DE MEDIACIÓN

Lugar y fecha: {{ caso.ciudad }}, {{ audiencia.fecha | fecha }}.
Mediador(a): {{ mediador.nombre }}.

## Participantes

{% para cada participante en participantes %}
**{{ participante.nombre }}**, {{ participante.calidad }}.
{% fin %}

## Situación

{{ situacion.descripcion }}

## Compromisos

{% para cada compromiso en compromisos %}
{{ compromiso.responsable }} se compromete a {{ compromiso.descripcion }}{% si compromiso.fecha %}, a más tardar el {{ compromiso.fecha | fecha }}{% fin %}.
{% fin %}

{% si seguimiento.fecha %}
Las partes acuerdan una reunión de seguimiento el {{ seguimiento.fecha | fecha }}.
{% fin %}

La mediación es voluntaria y confidencial; este documento recoge los compromisos que las partes aceptaron.$fuente$, null, '2026-10-20T00:00:00Z', '2026-10-20T00:00:00Z');

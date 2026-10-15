-- ActaFlow legal-case-service - V2: retira lo que el backend monolitico (v0.1) dejo en public.
-- La cola ya vive en legal_case.trabajo (V1). La tabla vieja nunca recibio trabajos: los workers
-- llegan en un bloque posterior. Se borra solo si existe, asi una base nueva no falla.

drop table if exists public.trabajo;
drop table if exists public.flyway_schema_history;

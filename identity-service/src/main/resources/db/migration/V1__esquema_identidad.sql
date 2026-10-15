-- ActaFlow identity-service - V1: esquema propio.
-- Flyway crea el esquema identity y guarda su historial en identity.flyway_schema_history.
-- Las tablas (cuentas, sesiones, organizaciones, llaves publicas) llegan en el bloque de identidad.

comment on schema identity is 'ActaFlow: identidad (cuentas, sesiones, organizaciones y llaves publicas)';

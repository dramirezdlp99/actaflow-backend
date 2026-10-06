# ActaFlow · Backend

API, gateway, workers y datos de ActaFlow, la plataforma de generación y maquetación de minutas
para audiencias de conciliación y mediaciones legales.

| | |
|---|---|
| Lenguaje | Java 21 |
| Framework | Spring Boot 4.1 (starters modulares), Jackson 3 |
| Datos | PostgreSQL 17 (Neon en producción) + Flyway |
| Puerto local | **9097** |
| Despliegue | Render (Web Service Docker, plan Free) |

## Arquitectura

Hexagonal por módulo, bajo `com.actaflow.backend`:

```
compartido/              transversal
  config/                ActaFlowProperties (todo por variables de entorno)
  seguridad/             FirmaHmac (dominio puro), FirmasServicios (BFF y motor)
  web/                   ApiError, FiltroFirmaBff, FiltroCabecerasSeguridad, ManejadorGlobalErrores
estado/                  primer módulo: GET /api/v1/status
  domain/                EstadoSistema, VerificacionComponente
  application/           ConsultarEstadoService + puertos VerificadorBaseDatos / VerificadorMotorIa
  infrastructure/        JdbcVerificadorBaseDatos, MotorIaHttpCliente, EstadoConfig
  web/                   EstadoController, RespuestaEstado
```

La capa de aplicación no conoce Spring: se cablea en `infrastructure/*Config`. Los *fakes* de
prueba viven solo en `src/test`.

### Seguridad entre servicios

- **BFF → backend:** toda ruta `/api/**` exige firma HMAC-SHA256 del BFF; sin firma, 401 con
  `ApiError`. El motivo del rechazo solo va al log.
- **Backend → motor de IA:** cada petición sale firmada con otro secreto, por HTTP/1.1 forzado.
- Mensaje canónico (idéntico en los tres repos):
  `timestamp \n MÉTODO \n ruta[?consulta] \n sha256_hex(cuerpo)`.
- Errores uniformes: `{"estado", "codigo", "mensaje", "ruta", "instante"}`.

### Decisiones por el plan gratuito

- `/actuator/health` **no** consulta la base de datos: Render lo llama a menudo y mantendría
  Neon despierto gastando horas-CU. El estado de la base va en `/api/v1/status`.
- Pool Hikari con `minimum-idle: 0`: las conexiones ociosas se cierran y Neon puede suspenderse.
- `/api/v1/status` verifica base y motor **en paralelo** con tiempos máximos: un motor dormido
  aparece como `CAIDO (puede estar despertando)` sin bloquear la respuesta, y la propia
  petición lo despierta.

## Desarrollo local (PowerShell)

**1. Maven Wrapper.** Copia `mvnw`, `mvnw.cmd` y `.mvn\wrapper\maven-wrapper.properties` desde
tu repo de PanelVault (son genéricos y ya funcionan en tu máquina).

**2. Bases de datos locales** (una sola vez, con `psql` de tu PostgreSQL local):

```powershell
psql -U postgres -c "CREATE USER actaflow PASSWORD '<clave-local>';"
psql -U postgres -c "CREATE DATABASE actaflow OWNER actaflow;"
psql -U postgres -c "CREATE USER actaflow_test PASSWORD 'actaflow_test';"
psql -U postgres -c "CREATE DATABASE actaflow_test OWNER actaflow_test;"
```

`actaflow_test` usa credenciales falsas y públicas (están en `application-test.yaml`).

**3. Variables de entorno** (una sola vez; después cierra por completo IntelliJ):

```powershell
[Environment]::SetEnvironmentVariable("ACTAFLOW_DB_URL", "jdbc:postgresql://localhost:5432/actaflow", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_DB_USERNAME", "actaflow", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_DB_PASSWORD", "<clave-local>", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_BFF_HMAC_SECRET", "<secreto-local-bff-32+>", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_ENGINE_URL", "http://localhost:8002", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_ENGINE_HMAC_SECRET", "<secreto-local-motor-32+>", "User")
```

El secreto del motor debe ser el mismo `ACTAFLOW_ENGINE_HMAC_SECRET` que usa el motor, y el
del BFF el mismo `ACTAFLOW_BFF_HMAC_SECRET` del frontend.

**4. Ejecutar y verificar:**

```powershell
.\mvnw spring-boot:run
.\mvnw test        # checkstyle (lint) + tests unitarios + integración con PostgreSQL real
```

## Runbook de despliegue (Render)

1. Render → **New → Web Service** → `dramirezdlp99/actaflow-backend`, runtime **Docker**,
   región **Virginia (US East)**, plan **Free**.
2. Health check path: `/actuator/health`.
3. Variables de entorno: `ACTAFLOW_DB_URL` (JDBC de Neon, conexión **directa**, con
   `?sslmode=require`), `ACTAFLOW_DB_USERNAME`, `ACTAFLOW_DB_PASSWORD`,
   `ACTAFLOW_BFF_HMAC_SECRET`, `ACTAFLOW_ENGINE_URL`, `ACTAFLOW_ENGINE_HMAC_SECRET`.
4. Auto-Deploy: **After CI Checks Pass**, rama `main`.

## Endpoints (bloque 0)

| Método y ruta | Firma BFF | Respuesta |
|---|---|---|
| `GET /actuator/health` | No | `{"status":"UP"}` sin detalles |
| `GET /api/v1/status` | Sí | Estado general (`OK`/`DEGRADADO`) y por capa: base de datos y motor de IA, con latencia y versión |

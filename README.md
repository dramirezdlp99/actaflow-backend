# ActaFlow · Backend (microservicios Java)

Microservicios Java de ActaFlow, la plataforma de generación y maquetación de minutas para
audiencias de conciliación y mediaciones legales. **No hay API REST:** cada servicio publica
servicios gRPC definidos primero en [`actaflow-contracts`](https://github.com/dramirezdlp99/actaflow-contracts)
(Contract-First, `.proto` como fuente única de verdad).

| | identity-service | legal-case-service |
|---|---|---|
| Responsabilidad | Cuentas, sesiones, organizaciones, llaves públicas | Casos, participantes, audiencias, plantillas, actas, trabajos |
| Puerto web (gRPC-Web + `/actuator/health`) | **9098** | **9097** |
| Puerto gRPC nativo (HTTP/2) | **9198** | **9197** |
| Esquema PostgreSQL | `identity` | `legal_case` |
| Llama a | — | motor de IA (Python) |

Java 21 · Spring Boot 4.1 · grpc-java 1.84.3 · protobuf 3.25.8 · PostgreSQL 17 (Neon) + Flyway.
El puerto 8080 no se usa nunca (lo ocupa Jenkins).

## Módulos Maven

```
pom.xml                  padre: versiones fijadas de gRPC y protobuf
contracts/               copia FIJADA de actaflow-contracts (ver contracts.lock.json)
rpc-contracts/           codigo Java GENERADO desde contracts/ (mensajes + stubs gRPC)
platform/                infraestructura comun, Java puro, sin Spring:
  seguridad/             FirmaHmac (mismo mensaje canonico que el BFF y el motor)
  grpc/                  ServidorGrpc (en memoria + nativo), interceptores de firma, ClienteGrpc
  grpcweb/               PasarelaGrpcWeb (servidor), CanalGrpcWeb (cliente), tramas
  estado/                VerificadorEstado, ServicioEstado, comprobaciones de BD y de servicios
  web/                   FiltroCabecerasSeguridad
identity-service/        microservicio de identidad
legal-case-service/      microservicio de expedientes (migrado del backend monolitico v0.1)
```

## Contratos: Contract-First y tipado de punta a punta

1. El contrato se diseña y versiona en `actaflow-contracts` (en inglés).
2. Aquí se fija una versión exacta: `contracts/` + `contracts.lock.json` (sha256 de cada archivo).
3. `rpc-contracts` genera las clases con `protoc` + `protoc-gen-grpc-java` en cada build: nadie
   escribe a mano un DTO ni un cliente.
4. CI comprueba que la copia es idéntica a la versión publicada (`check --remote`).

Actualizar a otra versión del contrato:

```powershell
node scripts/contracts.mjs sync v0.2.0     # descarga, copia y reescribe el lock
node scripts/contracts.mjs check           # verifica los sha256
.\mvnw verify
```

## Transporte: gRPC nativo y gRPC-Web

Cada servicio corre **un solo** servidor gRPC con dos entradas:

- **gRPC nativo sobre HTTP/2** (puertos 919x): local y CI.
- **gRPC-Web sobre HTTP/1.1** en el puerto web: producción. Render Free no expone HTTP/2
  público, así que una pasarela (filtro de Servlet) traduce gRPC-Web a una llamada en memoria al
  mismo servidor, con los mismos interceptores. Ver ADR 0003 en `actaflow-contracts`.

`ServiceStatus.served_via` informa por qué transporte llegó cada llamada. Lo pone el
servidor, el cliente no lo puede falsificar.

`legal-case-service` llama al motor de IA con `ClienteGrpc`: `ACTAFLOW_ENGINE_TRANSPORT=grpc`
(local, al puerto 8102 del motor) o `grpc-web` (Render, a su URL pública; lo fija el Dockerfile).

## Seguridad entre servicios

- Cada llamada lleva la firma HMAC-SHA256 en los metadatos `x-actaflow-timestamp` y
  `x-actaflow-signature`. Mensaje canónico, idéntico en Java, Python y TypeScript:
  `{timestamp}\nRPC\n/{paquete.Servicio}/{Metodo}\n{sha256 hex de los bytes protobuf de la peticion}`.
- **Un secreto por par:** BFF→identity (`ACTAFLOW_IDENTITY_HMAC_SECRET`), BFF→legal-case
  (`ACTAFLOW_LEGAL_CASE_HMAC_SECRET`), legal-case→motor (`ACTAFLOW_ENGINE_HMAC_SECRET`). Mismos
  nombres en el frontend y en el motor. Distintos en local y en producción.
- Sin firma válida: `UNAUTHENTICATED` y el servicio no se ejecuta. El motivo solo va al log.
- Cabeceras de seguridad (`nosniff`, `DENY`, CSP, HSTS, `no-store`) en todas las respuestas.

## Decisiones por el plan gratuito

- `/actuator/health` no consulta la base: Render lo llama a menudo y mantendría Neon despierto.
  El estado real va en `GetServiceStatus`.
- Hikari con `minimum-idle: 0`: Neon puede suspenderse.
- `GetServiceStatus` comprueba base y motor **en paralelo** (hilos virtuales) con tiempos
  máximos. Un motor dormido aparece `DOWN (puede estar despertando)` sin bloquear la respuesta.
- Una sola base de Neon para los dos servicios, con un esquema por servicio.

## Desarrollo local (PowerShell)

**1. Bases de datos** (una sola vez, con el `psql` de tu PostgreSQL local):

```powershell
psql -U postgres -c "CREATE USER actaflow PASSWORD '<clave-local>';"
psql -U postgres -c "CREATE DATABASE actaflow OWNER actaflow;"
psql -U postgres -c "CREATE USER actaflow_test PASSWORD 'actaflow_test';"
psql -U postgres -c "CREATE DATABASE actaflow_test OWNER actaflow_test;"
```

`actaflow_test` usa credenciales falsas y públicas (están en los `application-test.yaml`).

**2. Variables de entorno** (una sola vez; después cierra por completo IntelliJ). Genera cada
secreto con al menos 32 caracteres; los tres deben ser distintos:

```powershell
[Environment]::SetEnvironmentVariable("ACTAFLOW_DB_URL", "jdbc:postgresql://localhost:5432/actaflow", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_DB_USERNAME", "actaflow", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_DB_PASSWORD", "<clave-local>", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_IDENTITY_HMAC_SECRET", "<secreto-local-identity-32+>", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_LEGAL_CASE_HMAC_SECRET", "<secreto-local-legal-case-32+>", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_ENGINE_HMAC_SECRET", "<secreto-local-motor-32+>", "User")
```

**3. Ejecutar** (una terminal por servicio):

```powershell
.\mvnw -pl identity-service -am spring-boot:run
.\mvnw -pl legal-case-service -am spring-boot:run
```

**4. Verificar:**

```powershell
.\mvnw verify      # checkstyle + codigo generado + tests con PostgreSQL real
```

## Runbook de despliegue (Render)

**legal-case-service** (es el servicio existente `actaflow-backend`): en *Settings* cambia
**Dockerfile Path** a `./legal-case-service/Dockerfile` y **Docker Build Context Directory** a `.`
**antes** de hacer push. Variables: `ACTAFLOW_DB_URL`, `ACTAFLOW_DB_USERNAME`,
`ACTAFLOW_DB_PASSWORD`, `ACTAFLOW_LEGAL_CASE_HMAC_SECRET`, `ACTAFLOW_ENGINE_URL`,
`ACTAFLOW_ENGINE_HMAC_SECRET`. Health check: `/actuator/health`.

**identity-service** (nuevo): *New → Web Service* → este mismo repo, runtime Docker, región
Virginia, plan Free, Dockerfile Path `./identity-service/Dockerfile`, contexto `.`. Variables:
`ACTAFLOW_DB_URL`, `ACTAFLOW_DB_USERNAME`, `ACTAFLOW_DB_PASSWORD`,
`ACTAFLOW_IDENTITY_HMAC_SECRET`. Health check: `/actuator/health`.

Los dos: Auto-Deploy **After CI Checks Pass**, rama `main`.

## Servicios publicados (bloque BE-1)

| Servicio gRPC | identity | legal-case |
|---|---|---|
| `actaflow.system.v1.ServiceStatusService/GetServiceStatus` | Base de datos | Base de datos + motor de IA (estado anidado) |

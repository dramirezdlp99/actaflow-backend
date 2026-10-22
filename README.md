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
| Llama a | — | motor de IA (Python) e identity (solo la clave pública de los tokens) |

Java 21 · Spring Boot 4.1 · grpc-java 1.84.3 · protobuf 3.25.8 · PostgreSQL 17 (Neon) + Flyway.
El puerto 8080 no se usa nunca (lo ocupa Jenkins).

## Módulos Maven

```
pom.xml                  padre: versiones fijadas de gRPC y protobuf
contracts/               copia FIJADA de actaflow-contracts (ver contracts.lock.json)
rpc-contracts/           codigo Java GENERADO desde contracts/ (mensajes + stubs gRPC)
platform/                infraestructura comun, Java puro, sin Spring:
  seguridad/             FirmaHmac (mismo mensaje canonico que el BFF y el motor), TokenAcceso (JWT EdDSA)
  grpc/                  ServidorGrpc (en memoria + nativo), interceptores de firma, ClienteGrpc, ServicioPublico
  persistencia/          Bd: JDBC con SQL explicito y transacciones
  rpc/                   FalloRpc y Respuestas: errores con detalles FieldViolations del contrato
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
node scripts/contracts.mjs sync v0.3.0     # descarga, copia y reescribe el lock
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
secreto con al menos 32 caracteres; todos deben ser distintos entre si y distintos de los de
produccion:

```powershell
[Environment]::SetEnvironmentVariable("ACTAFLOW_DB_URL", "jdbc:postgresql://localhost:5432/actaflow", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_DB_USERNAME", "actaflow", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_DB_PASSWORD", "<clave-local>", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_IDENTITY_HMAC_SECRET", "<secreto-local-identity-32+>", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_IDENTITY_PEPPER", "<pepper-local-32+>", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_IDENTITY_TOKEN_SECRET", "<tokens-local-32+>", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_BOOTSTRAP_ORGANIZATION", "<nombre-del-centro>", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_BOOTSTRAP_ADMIN_EMAIL", "<tu-correo>", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_BOOTSTRAP_INVITATION_TOKEN", "<invitacion-local-32+>", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_LEGAL_CASE_HMAC_SECRET", "<secreto-local-legal-case-32+>", "User")
[Environment]::SetEnvironmentVariable("ACTAFLOW_ENGINE_HMAC_SECRET", "<secreto-local-motor-32+>", "User")
```

**3. Ejecutar.** Primero instala una vez los modulos compartidos; despues, una terminal por servicio:

```powershell
.\mvnw -q install -DskipTests
.\mvnw -pl identity-service spring-boot:run
.\mvnw -pl legal-case-service spring-boot:run
```

Repite el `install` cuando cambien `platform` o `rpc-contracts`.

**4. Verificar:**

```powershell
.\mvnw verify      # checkstyle + codigo generado + tests con PostgreSQL real
```

## Runbook de despliegue (Render)

**legal-case-service** (es el servicio existente `actaflow-backend`): en *Settings* cambia
**Dockerfile Path** a `./legal-case-service/Dockerfile` y **Docker Build Context Directory** a `.`
**antes** de hacer push. Variables: `ACTAFLOW_DB_URL`, `ACTAFLOW_DB_USERNAME`,
`ACTAFLOW_DB_PASSWORD`, `ACTAFLOW_LEGAL_CASE_HMAC_SECRET`, `ACTAFLOW_ENGINE_URL`,
`ACTAFLOW_ENGINE_HMAC_SECRET` y `ACTAFLOW_IDENTITY_URL` (la URL pública de identity-service en
Render; de ahí pide la clave pública que verifica los tokens, sin secreto). Health check:
`/actuator/health`.

**identity-service** (nuevo): *New → Web Service* → este mismo repo, runtime Docker, región
Virginia, plan Free, Dockerfile Path `./identity-service/Dockerfile`, contexto `.`. Variables:
`ACTAFLOW_DB_URL`, `ACTAFLOW_DB_USERNAME`, `ACTAFLOW_DB_PASSWORD`,
`ACTAFLOW_IDENTITY_HMAC_SECRET`, `ACTAFLOW_IDENTITY_PEPPER`, `ACTAFLOW_IDENTITY_TOKEN_SECRET` y,
solo hasta que te registres como administrador, `ACTAFLOW_BOOTSTRAP_ORGANIZATION`,
`ACTAFLOW_BOOTSTRAP_ADMIN_EMAIL` y `ACTAFLOW_BOOTSTRAP_INVITATION_TOKEN`. Health check:
`/actuator/health`. El pepper no se rota nunca: si cambia, nadie puede volver a entrar.

Los dos: Auto-Deploy **After CI Checks Pass**, rama `main`.

## Servicios publicados

| Servicio gRPC | identity | legal-case |
|---|---|---|
| `actaflow.system.v1.ServiceStatusService/GetServiceStatus` | Base de datos | Base de datos + motor de IA (estado anidado) |
| `actaflow.identity.v1.AuthService` | Registro por invitacion, ingreso en dos pasos (TOTP), refresco y cierre de sesion | — |
| `actaflow.identity.v1.AccountService` | Cuenta actual, codigos de recuperacion, cambio de contrasena | — |
| `actaflow.identity.v1.OrganizationService` | Organizacion, miembros, invitaciones, roles | — |
| `actaflow.identity.v1.KeyDirectoryService` | Claves publicas de los miembros | — |
| `actaflow.identity.v1.TokenVerificationService` | Clave publica Ed25519 que firma los tokens (**pública**, sin firma HMAC, como un JWKS) | — |
| `actaflow.cases.v1.CaseService` | — | Crear, ver, listar y editar expedientes; estados y línea de tiempo |
| `actaflow.cases.v1.ParticipantService` | — | Participantes, invitaciones por correo y reparto de la clave del caso |
| `actaflow.templates.v1.TemplateService` | — | Biblioteca de plantillas (**lectura pública**, sin sesión), editor, vista previa, versiones y publicación |
| `actaflow.hearings.v1.HearingService` | — | Audiencias, segmentos de audio sellados, transcripción y extracción de entidades (cola asíncrona), eventos en vivo |

## Identidad (bloque BE-2)

- **Conocimiento cero:** la contrasena nunca sale del navegador. El cliente deriva con PBKDF2
  (600 000 iteraciones minimo) un verificador; el servidor guarda solo `HMAC(pepper, verificador)`.
  La clave privada de cada persona llega cifrada (por contrasena y por codigo de recuperacion).
- **Segundo factor obligatorio:** TOTP (RFC 6238, ventana de ±30 s, cada codigo sirve una vez) o
  uno de los 10 codigos de recuperacion. El secreto TOTP se guarda cifrado con AES-GCM.
- **Bloqueo:** 5 intentos fallidos bloquean la cuenta 15 minutos. Los errores nunca revelan si
  el correo existe.
- **Sesiones:** token de acceso JWT EdDSA de 15 minutos y token de refresco de 30 dias que rota
  en cada uso; reusar uno ya rotado revoca toda la familia (deteccion de robo).
- **Sin servicio de correo:** invitar devuelve el token una sola vez y el administrador comparte
  el enlace. La base guarda solo su hash.
- **Persistencia:** JDBC directo sobre el esquema `identity` (migracion `V2`).

## Expedientes (bloque BE-3)

- **Sesión verificada localmente:** cada llamada a `CaseService` y `ParticipantService` exige la
  firma HMAC del BFF **y** el token de acceso de la persona (`authorization: Bearer`). El token se
  verifica en legal-case con la clave pública Ed25519 de identity (`TokenAcceso`, en `platform`),
  sin llamar a identity en cada petición. Las claves se piden una vez por hora, y ante un id de
  clave desconocido como mucho una vez cada 30 s.
- **Acceso solo para participantes:** quien no participa recibe `NOT_FOUND`, nunca
  `PERMISSION_DENIED`, así no se revela que el caso existe. Los administradores de la organización
  no ven el contenido de casos ajenos.
- **Cifrado de punta a punta:** el título y el resumen viajan cifrados con la clave del caso
  (`EncryptedBlob`) y esa clave solo existe envuelta para la clave pública de cada participante
  (`WrappedKey`). El backend guarda los mensajes protobuf tal cual y no puede abrirlos.
- **Invitaciones por correo:** quien no tiene cuenta (o no ha entrado) queda `INVITED`; al entrar
  con su cuenta de la misma organización el caso aparece solo (`AWAITING_KEY`) y cualquier
  participante que ya tenga la clave se la entrega (`GrantCaseKey` → `ACTIVE`).
- **Reglas:** concurrencia optimista (`expected_revision`, si cambió → `ABORTED`); escrituras
  idempotentes por cuenta y clave; transiciones de estado válidas (abierto → en audiencia →
  acuerdo / no acuerdo → archivado, con suspensión y reapertura); el caso siempre conserva un
  conciliador; las partes llevan lado y calidad (regla CEL del contrato).
- **Persistencia:** migración `V3` en el esquema `legal_case` (`caso`, `participante`,
  `evento_caso`, `idempotencia`).

## Plantillas (bloque BE-5)

- **Biblioteca pública:** `ListPublishedTemplates` y `GetPublishedTemplate` piden la firma HMAC del
  BFF pero no una sesión. Así el frontend las genera con ISR. Las plantillas no llevan datos de
  casos, por eso van en claro.
- **Solo administradores** validan, previsualizan, guardan borradores y publican, y solo la
  organización autora edita sus plantillas. Las del sistema (migración `V4`, sin organización) no
  las edita nadie: sirven de base para una propia.
- **Versiones:** guardar reemplaza el borrador más nuevo o abre uno nuevo si la última versión ya
  está publicada. Publicar deja la anterior como `SUPERSEDED` (a lo sumo una publicada por
  plantilla, garantizado por un índice único parcial). `ListOrganizationTemplates` y
  `GetTemplateVersion` (contratos v0.3.0) dejan encontrar y volver a abrir los borradores.
- **Lenguaje** (`plantillas/Lenguaje`): `{{ ruta | formato }}`, `{% si ... %}` / `{% sino %}` /
  `{% fin %}`, `{% para cada x en lista %}` y comentarios `{# #}`. Se reportan diagnósticos en
  español con línea y columna. Una fuente con errores no se guarda (`INVALID_ARGUMENT` con
  `TEMPLATE_SYNTAX_ERROR` y el detalle `TemplateDiagnostics`). Las variables, su tipo, su
  etiqueta y su origen se deducen del uso.
- **Formatos:** `moneda` ($1.500.000), `moneda_en_letras` (un millón quinientos mil pesos
  ($1.500.000)), `fecha`, `fecha_en_letras`, `numero`, `numero_en_letras`, `mayusculas` y
  `minusculas`.
- **HTML seguro** (`plantillas/Documento`): todo el texto y los valores se escapan, y solo salen
  `h2`, `h3`, `p`, `br`, `strong`, `span.valor` y `mark.falta` (un valor que falta se ve como
  `[ruta]`). La salida tiene un tope de tamaño, para que una lista enorme en bucles anidados no
  agote la memoria.

## Audiencias (bloque BE-6)

- **El backend no puede leer nada de la audiencia.** El navegador sella cada intervención (hasta
  30 s) para el motor de IA (`SealedEnvelope`): el audio va cifrado con una clave de contenido K,
  K va envuelta para la clave pública del motor y, aparte, cifrada con la clave del caso. Legal-case
  guarda y reenvía esos bytes; el motor devuelve la transcripción cifrada con K, que solo abren los
  participantes.
- **Solo el conciliador** inicia y termina la audiencia (a lo sumo una en curso por expediente,
  garantizado por un índice único parcial). Graban, piden la extracción y revisan las entidades el
  conciliador y los asistentes; las partes ven todo.
- **Idempotencia:** un segmento se identifica por (audiencia, número de orden). Reenviar el mismo
  segmento devuelve el guardado; otro audio con el mismo número da `ALREADY_EXISTS`.
- **Cola de trabajos** (`aplicacion/ColaTrabajos`, tabla `trabajo` de `V1`): un hilo revisa la cola
  cada 2 s y toma los trabajos con `FOR UPDATE SKIP LOCKED`. La llamada al motor va fuera de la
  transacción, con un plazo de 180 s (en Render Free el motor puede estar despertando). Los errores
  pasajeros (`UNAVAILABLE`, `DEADLINE_EXCEEDED`...) se reintentan con espera creciente (10 s, 20 s,
  40 s...) hasta 5 intentos; los definitivos (`INVALID_ARGUMENT`, sobre ilegible) marcan el segmento
  como `FAILED`. **El audio sellado se borra** en cuanto hay transcripción o fallo definitivo.
- **Revisión de entidades:** `ConfirmEntities` usa concurrencia optimista (`expected_revision`); si
  otra persona guardó antes, responde `ABORTED`.
- **Eventos en vivo** (`WatchHearingEvents`, server streaming por ambos transportes): cada cambio de
  un segmento o una extracción, y el fin de la audiencia, queda en `evento_audiencia` con un número
  creciente. El flujo los consulta cada segundo, manda un latido cada 20 s sin cambios y se cierra
  al terminar la audiencia o a los 5 minutos; el cliente se reconecta con el último número que
  recibió y no pierde nada.

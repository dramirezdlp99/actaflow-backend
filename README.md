<div align="center">

# ⚙️ ActaFlow · Backend

**Microservicios Java de identidad y expedientes. Sin una sola ruta REST: todo es gRPC, definido primero en los
contratos.**

*Java 21 · Spring Boot 4.1 · grpc-java 1.84.3 · PostgreSQL 17 (Neon) · Flyway · Render*

![Java](https://img.shields.io/badge/Java-21-1d3557)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-9b2335)
![gRPC](https://img.shields.io/badge/gRPC-nativo%20%2B%20gRPC--Web-1d3557)
![Contratos](https://img.shields.io/badge/contratos-v0.4.0-1d3557)
![Pruebas](https://img.shields.io/badge/pruebas-164%20en%20verde-2e7d32)

| | |
|---|---|
| 👨‍💻 **Desarrollado por** | David Fernando Ramírez de la Parra |
| 👨‍🏫 **Profesor** | Jhonatan Andrés Mideros Narváez |
| 📚 **Asignatura** | Programación Orientada a la Web |
| 🏛️ **Institución** | Universidad Cooperativa de Colombia |

</div>

---

## 🧭 ¿Qué es ActaFlow?

ActaFlow es una **plataforma web para generar y maquetar minutas y actas de audiencias de conciliación y mediación**
(caso de estudio 3), para centros de conciliación, notarías y despachos jurídicos. Mientras la audiencia ocurre, el
sistema graba cada intervención, la transcribe con un motor de IA propio, extrae las entidades jurídicas y, con lo que
una persona revisa, llena la plantilla del acta. Al final genera el **PDF sellado**, que las partes **aceptan con su
segundo factor** y que cualquiera puede **verificar**.

| Repositorio | Qué es |
|---|---|
| 📜 [actaflow-contracts](https://github.com/dramirezdlp99/actaflow-contracts) | La API (Contract-First) |
| 🖥️ [actaflow-frontend](https://github.com/dramirezdlp99/actaflow-frontend) | Interfaz web y BFF |
| ⚙️ **actaflow-backend** (este) | Microservicios de identidad y expedientes |
| 🧠 [actaflow-ai-engine](https://github.com/dramirezdlp99/actaflow-ai-engine) | Motor de IA: voz a texto, entidades y PDF |

> 🛡️ **El backend es el corazón:** aquí vive toda la lógica de negocio y toda la seguridad. El frontend se considera
> "regalado" al atacante; por eso ninguna regla depende de él, y cada permiso se vuelve a comprobar aquí.

---

## 🏗️ Los dos microservicios

| | 🔑 identity-service | 📁 legal-case-service |
|---|---|---|
| **Responsabilidad** | Cuentas, sesiones, organizaciones, llaves públicas, prueba de presencia | Expedientes, participantes, audiencias, plantillas, actas, sello, borrador colaborativo, cola de trabajos |
| **Puerto web** (gRPC-Web + `/actuator/health`) | **9098** | **9097** |
| **Puerto gRPC nativo** (HTTP/2) | **9198** | **9197** |
| **Esquema PostgreSQL** | `identity` (migraciones V1 y V2) | `legal_case` (migraciones V1 a V7) |
| **Llama a** | — | Motor de IA (Python) e identity (solo la clave pública de los tokens) |

> El puerto 8080 no se usa nunca: lo ocupa Jenkins.

### 🧱 Capas

```
 Capa cliente      navegador (todo cifrado ahí)
       │
 Capa de borde     BFF de Next.js: única puerta, firma HMAC por pareja de servicios, oculta las URLs
       │  gRPC / gRPC-Web
 Capa aplicación   identity-service · legal-case-service   ← este repositorio
       │
 Capa asíncrona    cola de trabajos (transcribir, extraer, renderizar el acta) con reintentos
       │
 Capa de IA        ai-engine (solo lo llama legal-case, con su propio secreto)
       │
 Capa de datos     PostgreSQL en Neon, un esquema por servicio
       │
 DevOps            GitHub Actions + Docker + Render (despliegue manual tras el CI en verde)
```

**El frontend nunca toca la base de datos**, y el navegador nunca habla con estos servicios: solo el BFF, firmando
cada llamada.

---

## 📦 Módulos Maven (POO, sin funciones sueltas)

```
pom.xml                  padre: versiones fijadas de gRPC y protobuf
contracts/               copia FIJADA de actaflow-contracts (ver contracts.lock.json)
rpc-contracts/           código Java GENERADO desde contracts/ (mensajes + stubs gRPC)
platform/                infraestructura común, Java puro, sin Spring:
  seguridad/             FirmaHmac (mismo mensaje canónico que el BFF y el motor), TokenAcceso (JWT EdDSA y prueba de presencia)
  grpc/                  ServidorGrpc (en memoria + nativo), interceptores de firma, ClienteGrpc, ServicioPublico
  persistencia/          Bd: JDBC con SQL explícito y transacciones
  rpc/                   FalloRpc y Respuestas: errores con detalles FieldViolations del contrato
  grpcweb/               PasarelaGrpcWeb (servidor), CanalGrpcWeb (cliente), tramas
  estado/                VerificadorEstado, ServicioEstado, comprobaciones de BD y de servicios
  web/                   FiltroCabecerasSeguridad
identity-service/        microservicio de identidad
legal-case-service/      microservicio de expedientes
  aplicacion/            casos de uso: ServicioCasos, ServicioAudiencias, ServicioActas, ServicioColaboracion, ColaTrabajos…
  grpc/                  adaptadores gRPC: validan con las reglas del contrato y delegan
  plantillas/            el lenguaje de plantillas (analizador, diagnósticos y HTML seguro)
  sello/                 Der y Sellador: la firma CMS del acta
  seguridad/             sesión: verificación local del token con la clave pública de identity
```

Cada capa tiene una responsabilidad: los adaptadores gRPC validan y traducen, los servicios de aplicación tienen la
lógica y las transacciones, y `platform` concentra la infraestructura que comparten los dos servicios.

---

## 📐 Contract-First y tipado de punta a punta

1. El contrato se diseña y se versiona en [actaflow-contracts](https://github.com/dramirezdlp99/actaflow-contracts),
   en inglés.
2. Aquí se fija una versión exacta: `contracts/` + `contracts.lock.json` (SHA-256 de cada archivo).
3. `rpc-contracts` genera las clases con protoc + protoc-gen-grpc-java en **cada build**: nadie escribe a mano un DTO
   ni un cliente.
4. El CI comprueba que la copia es idéntica a la versión publicada (`check --remote`).

La cadena de tipos no se rompe: **PostgreSQL → Java (código generado) → Protobuf en el cable → TypeScript generado →
componentes**. Si el contrato cambia, el proyecto deja de compilar, en vez de fallar en producción.

```powershell
node scripts/contracts.mjs sync v0.4.0     # descarga, copia y reescribe el lock
node scripts/contracts.mjs check           # verifica los sha256
.\mvnw verify
```

---

## 🛰️ Transporte: gRPC nativo y gRPC-Web

Cada servicio corre **un solo servidor gRPC con dos entradas**:

- **gRPC nativo sobre HTTP/2** (puertos 919x): local y CI.
- **gRPC-Web sobre HTTP/1.1** en el puerto web: producción. Render Free no expone HTTP/2 público, así que una
  **pasarela** (filtro de Servlet) traduce gRPC-Web a una llamada en memoria al mismo servidor, con los mismos
  interceptores (ADR 0003).

`ServiceStatus.served_via` informa por qué transporte llegó cada llamada. Lo pone el servidor; el cliente no lo puede
falsificar. legal-case llama al motor con `ClienteGrpc`: `ACTAFLOW_ENGINE_TRANSPORT=grpc` en local o `grpc-web` en
Render.

---

## 🔐 Seguridad

- 🔏 **Firma HMAC-SHA256 en cada llamada**, en los metadatos `x-actaflow-timestamp` y `x-actaflow-signature`. El
  mensaje canónico es idéntico en Java, Python y TypeScript:
  `{timestamp}\nRPC\n/{paquete.Servicio}/{Metodo}\n{sha256 hex de los bytes protobuf de la petición}`.
- 🗝️ **Un secreto por pareja de servicios:** BFF→identity (`ACTAFLOW_IDENTITY_HMAC_SECRET`), BFF→legal-case
  (`ACTAFLOW_LEGAL_CASE_HMAC_SECRET`) y legal-case→motor (`ACTAFLOW_ENGINE_HMAC_SECRET`). Son distintos en local y en
  producción, y siempre van **por variables de entorno**, nunca en el código ni en el historial.
- 🚫 Sin firma válida: `UNAUTHENTICATED`, y el servicio no se ejecuta. El motivo solo va al log.
- 🙈 **A quien no participa en un caso se le responde `NOT_FOUND`**, nunca `PERMISSION_DENIED`: no se revela que el
  caso existe. Los administradores de la organización tampoco ven el contenido de casos ajenos.
- 🔒 **El backend no puede leer el contenido:** títulos, resúmenes, audio, transcripciones, entidades, borradores y
  actas llegan cifrados desde el navegador y se guardan tal cual.
- 🧾 Cabeceras de seguridad (nosniff, DENY, CSP, HSTS, no-store) en todas las respuestas.

---

## 📡 Servicios publicados

| Servicio gRPC | identity | legal-case |
|---|---|---|
| `system.v1.ServiceStatusService` | Base de datos | Base de datos + motor de IA (estado anidado) |
| `identity.v1.AuthService` | Registro por invitación, ingreso en dos pasos (TOTP), refresco, cierre de sesión y **prueba de presencia** | — |
| `identity.v1.AccountService` | Cuenta actual, códigos de recuperación, cambio de contraseña | — |
| `identity.v1.OrganizationService` | Organización, miembros, invitaciones, roles | — |
| `identity.v1.KeyDirectoryService` | Claves públicas de los miembros | — |
| `identity.v1.TokenVerificationService` | Clave pública Ed25519 que firma los tokens (**pública**, como un JWKS) | — |
| `cases.v1.CaseService` | — | Crear, ver, listar y editar expedientes; estados y línea de tiempo |
| `cases.v1.ParticipantService` | — | Participantes, invitaciones y reparto de la clave del caso |
| `templates.v1.TemplateService` | — | Biblioteca de plantillas (**lectura pública**), editor, vista previa, versiones y publicación |
| `hearings.v1.HearingService` | — | Audiencias, segmentos de audio sellados, transcripción y extracción (cola asíncrona), **eventos en vivo** |
| `crypto.v1.ProcessorKeyService` | — | Clave pública del motor (pedida al motor y guardada 10 min) |
| `minutes.v1.MinuteService` | — | Actas: crear, cerrar, documento final sellado, aceptación con prueba de presencia y **verificación pública** |
| `collaboration.v1.CollaborationService` | — | **Borrador colaborativo** cifrado: orden total de los cambios, idempotente, compactación |

---

## 🔑 Identidad

- 🧠 **Conocimiento cero:** la contraseña nunca sale del navegador. El cliente deriva con PBKDF2 (600 000 iteraciones)
  un verificador, y el servidor guarda solo `HMAC(pepper, verificador)`. La clave privada de cada persona llega cifrada
  dos veces: con su contraseña y con su código de recuperación.
- 📱 **Segundo factor obligatorio:** TOTP (RFC 6238, ventana de ±30 s, cada código sirve una sola vez) o uno de los 10
  códigos de recuperación. El secreto TOTP se guarda cifrado con AES-GCM.
- ⛔ **Bloqueo:** 5 intentos fallidos bloquean la cuenta 15 minutos. Los errores nunca revelan si el correo existe.
- 🎟️ **Sesiones:** token de acceso JWT **EdDSA de 15 minutos** y token de refresco de 30 días que **rota en cada uso**.
  Reusar uno ya rotado revoca toda la familia (detección de robo).
- ✉️ **Alta solo por invitación** (no hay registro abierto). El primer administrador se crea en el primer arranque con
  `ACTAFLOW_BOOTSTRAP_*`. Después, el administrador invita a cada persona con su rol, y el enlace se comparte una sola
  vez: la base guarda solo su hash.
- ✅ **Prueba de presencia** (`ConfirmPresence`): con sesión y un código TOTP **nuevo**, identity firma una prueba de
  **2 minutos** con un solo propósito, por ejemplo `accept-minute:<documento>:<sha256>`. No sirve como token de acceso,
  y un token de acceso tampoco vale como prueba.

## 📁 Expedientes

- **Sesión verificada localmente:** legal-case verifica el token con la clave pública Ed25519 de identity, sin
  llamarlo en cada petición. Pide las claves una vez por hora y, ante un id desconocido, como mucho una vez cada 30 s.
- **Cifrado de punta a punta:** el título y el resumen viajan cifrados con la clave del caso (`EncryptedBlob`), y esa
  clave solo existe envuelta para la clave pública de cada participante (`WrappedKey`).
- **Reglas:** concurrencia optimista (`expected_revision`; si alguien guardó antes, `ABORTED`), escrituras idempotentes,
  y transiciones de estado válidas: abierto → en audiencia → acuerdo o no acuerdo → archivado, con suspensión y
  reapertura. El caso conserva siempre un conciliador, y las partes llevan lado y calidad (regla CEL del contrato).

## 📝 Plantillas

- **Biblioteca pública** (sin sesión) para que el frontend la genere con **ISR**. Solo los administradores editan y
  publican.
- **Versiones:** publicar deja la anterior como `SUPERSEDED`, con a lo sumo una publicada por plantilla (índice único
  parcial).
- **Lenguaje propio** (`plantillas/Lenguaje`): `{{ ruta | formato }}`, `{% si … %}`, `{% sino %}`,
  `{% para cada x en lista %}` y comentarios. Da diagnósticos en español con línea y columna. Formatos: `moneda`,
  `moneda_en_letras`, `fecha`, `fecha_en_letras`, `numero_en_letras`, `mayusculas`…
- **HTML seguro:** todo se escapa, y solo salen `h2`, `h3`, `p`, `br`, `strong`, `span.valor` y `mark.falta`.

## 🎙️ Audiencias y cola asíncrona

- **El backend no puede oír la audiencia:** cada intervención llega **sellada para el motor** (`SealedEnvelope`). legal-case
  la guarda y la reenvía; el motor devuelve la transcripción cifrada, que solo abren los participantes.
- **Cola de trabajos** (`ColaTrabajos`): un hilo revisa la cola cada 2 s y toma trabajos con
  `FOR UPDATE SKIP LOCKED`.
  - La llamada al motor va fuera de la transacción, con un plazo de 180 s.
  - Los errores pasajeros se reintentan con espera creciente (10 s, 20 s, 40 s…, hasta 5 intentos).
  - El audio sellado se borra en cuanto hay transcripción.
- **Eventos en vivo** (`WatchHearingEvents`, server streaming por los dos transportes): latido cada 15 s. El flujo se
  cierra a los 50 s, porque la pasarela de Render corta a los 60 s, y el cliente se reconecta desde el último evento
  sin perder ninguno.
- **Hilos virtuales sin bloqueo del portador:** las esperas bloqueantes usan `ReentrantLock` y no `synchronized`.
  En Render Free, con una sola CPU, un portador bloqueado congelaba todo el servicio; el error se reprodujo con
  paralelismo 1 y se corrigió.

## 📜 Actas y sello de integridad

- **Nadie en el servidor lee el acta** (ADR 0004). El navegador llena la plantilla y sella el contenido final para el
  motor. legal-case lo encola (`RENDERIZAR_ACTA`), y el motor genera el PDF y devuelve el PDF cifrado más su SHA-256.
- **Sello** (`sello/Sellador`): firma **CMS separada (RFC 5652)** sobre el SHA-256, con atributos firmados y el
  certificado de ActaFlow dentro.
  - La clave ECDSA P-256 y el certificado se derivan de `ACTAFLOW_SEAL_SECRET`, así que sobreviven a los reinicios
    sin archivos.
  - Se comprueba con
    `openssl cms -verify -binary -inform DER -in sello.p7s -content acta.pdf -noverify`.
  - **No es una firma digital certificada** en los términos de la Ley 527 de 1999.
- **Estados:** `DRAFT → SEALING → AWAITING_ACCEPTANCE → FINAL`. Si el motor falla del todo, el acta vuelve a `DRAFT`.
- **Aceptación con segundo factor:** la parte confirma su código en identity y legal-case exige esa prueba firmada,
  para su cuenta y para el SHA-256 exacto del PDF que leyó.
- **Verificación pública** (`VerifyDocument`, sin sesión): por SHA-256 dice si el acta es auténtica, el radicado y
  cuántas partes aceptaron. No revela nada más del caso.

## 🤝 Borrador colaborativo (offline-first)

- Cada cambio del borrador llega **cifrado con la clave del caso** y con un `update_id` generado en el navegador.
  legal-case solo le asigna un **número de orden por acta**, y todos aplican los cambios en ese orden: el último gana
  por campo, y así todos convergen al mismo borrador.
- **Idempotente:** la bandeja de salida sin conexión puede reenviar y nada se duplica, incluso después de compactar
  (migración V7).
- Editan el conciliador y los asistentes, y solo mientras el acta es borrador. Leen todos los participantes.
- `IssueCollaborationTicket` queda `UNIMPLEMENTED`: en Render Free los servicios hablan gRPC-Web por HTTP/1.1, así
  que el frontend sigue el borrador desde un **Shared Worker** con una sola consulta por navegador.

## 💸 Decisiones por el plan gratuito

- `/actuator/health` no consulta la base, porque Render lo llama a menudo y mantendría Neon despierto. El estado
  real va en `GetServiceStatus`.
- Hikari con `minimum-idle: 0`, porque Neon puede suspenderse.
- `GetServiceStatus` comprueba la base y el motor en paralelo (hilos virtuales), con tiempos máximos.
- Una sola base de Neon para los dos servicios, con un esquema por servicio.

---

## 🛠️ Desarrollo local (PowerShell)

**1. Bases de datos** (una sola vez):

```powershell
psql -U postgres -c "CREATE USER actaflow PASSWORD '<clave-local>';"
psql -U postgres -c "CREATE DATABASE actaflow OWNER actaflow;"
psql -U postgres -c "CREATE USER actaflow_test PASSWORD 'actaflow_test';"
psql -U postgres -c "CREATE DATABASE actaflow_test OWNER actaflow_test;"
```

`actaflow_test` usa credenciales falsas y públicas, que están en los `application-test.yaml`.

**2. Variables de entorno** (una sola vez; después cierra por completo IntelliJ). Cada secreto debe tener al menos
32 caracteres, todos distintos entre sí y distintos de los de producción:

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
[Environment]::SetEnvironmentVariable("ACTAFLOW_SEAL_SECRET", "<sello-local-32+>", "User")
```

**3. Ejecutar:**

```powershell
.\mvnw -q install -DskipTests
.\mvnw -pl identity-service spring-boot:run
.\mvnw -pl legal-case-service spring-boot:run
```

**4. Verificar:**

```powershell
.\mvnw verify      # checkstyle + código generado + pruebas con PostgreSQL real
```

---

## 🚀 Despliegue (Render)

| Servicio | Dockerfile | Variables |
|---|---|---|
| **legal-case-service** | `./legal-case-service/Dockerfile` (contexto `.`) | `ACTAFLOW_DB_URL`, `ACTAFLOW_DB_USERNAME`, `ACTAFLOW_DB_PASSWORD`, `ACTAFLOW_LEGAL_CASE_HMAC_SECRET`, `ACTAFLOW_ENGINE_URL`, `ACTAFLOW_ENGINE_HMAC_SECRET`, `ACTAFLOW_SEAL_SECRET`, `ACTAFLOW_IDENTITY_URL` |
| **identity-service** | `./identity-service/Dockerfile` (contexto `.`) | `ACTAFLOW_DB_URL`, `ACTAFLOW_DB_USERNAME`, `ACTAFLOW_DB_PASSWORD`, `ACTAFLOW_IDENTITY_HMAC_SECRET`, `ACTAFLOW_IDENTITY_PEPPER`, `ACTAFLOW_IDENTITY_TOKEN_SECRET` y, solo para crear el primer administrador, `ACTAFLOW_BOOTSTRAP_*` |

Los dos usan runtime Docker, región Virginia, plan Free y health check `/actuator/health`. Se despliegan a mano después
del CI en verde: **Manual Deploy → Deploy latest commit**. El pepper y el secreto del sello **no se rotan**.

---

## 🧪 Calidad

| Módulo | Pruebas |
|---|---|
| platform | **70** (firma HMAC con vectores compartidos, tokens, pasarela gRPC-Web, estado) |
| identity-service | **28** (flujo completo de identidad por gRPC, TOTP con los vectores del RFC 6238, prueba de presencia) |
| legal-case-service | **66** (expedientes, plantillas, audiencias con motor falso, actas con sello CMS, borrador colaborativo, los dos transportes) |

Las pruebas corren contra **PostgreSQL real**. Además, se aplicaron **pruebas de mutación** a mano (romper una regla a
propósito y comprobar que alguna prueba falla). El CI corre checkstyle, el código generado, todas las pruebas y los
contenedores Docker en cada push.

---

<div align="center">

**ActaFlow** · Proyecto académico de Ingeniería de Software · Sin datos reales de personas ni de procesos

Desarrollado por **David Fernando Ramírez de la Parra** · Profesor **Jhonatan Andrés Mideros Narváez**<br>
**Programación Orientada a la Web** · **Universidad Cooperativa de Colombia**

</div>

# ActaFlow - legal-case-service (Spring Boot 4, Java 21). Imagen para Render Free (512 MB de RAM).
# Contexto de build: la RAIZ del repo (Render: Dockerfile Path ./legal-case-service/Dockerfile, Docker Context .).

# --- Etapa 1: compilacion con Maven (no se usa mvnw para evitar problemas de permisos y CRLF) ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Todos los pom: Maven necesita el reactor completo aunque solo construya este servicio.
COPY pom.xml ./
COPY config ./config
COPY rpc-contracts/pom.xml rpc-contracts/pom.xml
COPY platform/pom.xml platform/pom.xml
COPY identity-service/pom.xml identity-service/pom.xml
COPY legal-case-service/pom.xml legal-case-service/pom.xml
# Contratos fijados (fuente unica de verdad): rpc-contracts genera el codigo desde aqui en el build.
# Despues, solo el codigo que necesita este servicio.
COPY contracts ./contracts
COPY platform/src platform/src
COPY legal-case-service/src legal-case-service/src
RUN mvn -B -q -pl legal-case-service -am -DskipTests -Dcheckstyle.skip=true package

# --- Etapa 2: solo el JRE y el jar ---
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home actaflow
WORKDIR /app
COPY --from=build /build/legal-case-service/target/legal-case-service.jar /app/app.jar
USER actaflow

# Render Free no expone HTTP/2 publico: el gRPC nativo se apaga y todo entra por gRPC-Web
# en el puerto web (PORT), junto al chequeo de salud /actuator/health.
ENV ACTAFLOW_GRPC_NATIVE_PORT=-1
# El motor de IA tambien vive en Render Free: se le llama por gRPC-Web (HTTP/1.1) a su URL publica.
ENV ACTAFLOW_ENGINE_TRANSPORT=grpc-web
# Lo mismo para identity-service, de donde solo se pide la clave publica de los tokens.
ENV ACTAFLOW_IDENTITY_TRANSPORT=grpc-web

# Memoria acotada al contenedor, GC serial (menos hilos y RAM) y arranque rapido en una CPU
# fraccionada. ExitOnOutOfMemoryError deja que Render reinicie el servicio limpio.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError"

EXPOSE 9097
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# ActaFlow - identity-service (Spring Boot 4, Java 21). Imagen para Render Free (512 MB de RAM).
# Contexto de build: la RAIZ del repo (Render: Dockerfile Path ./identity-service/Dockerfile, Docker Context .).

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
COPY identity-service/src identity-service/src
RUN mvn -B -q -pl identity-service -am -DskipTests -Dcheckstyle.skip=true package

# --- Etapa 2: solo el JRE y el jar ---
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home actaflow
WORKDIR /app
COPY --from=build /build/identity-service/target/identity-service.jar /app/app.jar
USER actaflow

# Render Free no expone HTTP/2 publico: el gRPC nativo se apaga y todo entra por gRPC-Web
# en el puerto web (PORT), junto al chequeo de salud /actuator/health.
ENV ACTAFLOW_GRPC_NATIVE_PORT=-1

# Memoria acotada al contenedor, GC serial (menos hilos y RAM) y arranque rapido en una CPU
# fraccionada. ExitOnOutOfMemoryError deja que Render reinicie el servicio limpio.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError"

EXPOSE 9098
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

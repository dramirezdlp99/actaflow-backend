# ActaFlow - Backend (Spring Boot 4, Java 21). Imagen para Render Free (512 MB de RAM).

# --- Etapa 1: compilacion con Maven (no se usa mvnw para evitar problemas de permisos y CRLF) ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY config ./config
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests -Dcheckstyle.skip=true package

# --- Etapa 2: solo el JRE y el jar ---
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home actaflow
WORKDIR /app
COPY --from=build /build/target/actaflow-backend.jar /app/app.jar
USER actaflow

# Memoria acotada al contenedor, GC serial (menos hilos y RAM) y arranque rapido en una CPU
# fraccionada. ExitOnOutOfMemoryError deja que Render reinicie el servicio limpio.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError"

EXPOSE 9097
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

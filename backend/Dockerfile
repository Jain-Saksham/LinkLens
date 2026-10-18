# syntax=docker/dockerfile:1

# --- Build: compile on a real JDK 21 and split the jar into cacheable layers ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests run in CI (they need Docker for Testcontainers); the image build only packages.
RUN mvn -B -q -DskipTests package \
 && cp target/linklens-backend-*.jar /app.jar \
 && java -Djarmode=tools -jar /app.jar extract --layers --destination /layers

# --- Runtime: JRE only, non-root ---
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S linklens && adduser -S linklens -G linklens
WORKDIR /app
# Least-changing layers first, so a code change only re-ships the small application layer.
# Result: /app/app.jar (thin, Class-Path → lib/) + /app/lib/*.jar
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./

# Sized for small containers (Render Free: 512 MB, 0.1 CPU):
#   MaxRAMPercentage  heap ~65% of the container limit, leaving room for metaspace/threads/buffers
#   SerialGC          least memory and CPU overhead on a fraction of a core
#   TieredStopAtLevel=1  C1 only: much faster startup, slightly lower peak throughput
#   ExitOnOutOfMemoryError  crash and get restarted instead of limping along
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError"

# Class Data Sharing: a training run records the classes the app loads into app.jsa; at runtime the
# JVM maps that archive instead of parsing and verifying ~15k classes, which is most of the startup
# CPU on a 0.1-core instance. The training run has no database, so it stops at context refresh and
# skips the steps that would connect (Flyway, Hibernate's JDBC metadata lookup). If the archive ever
# doesn't match, the JVM just starts without it.
RUN java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh -jar app.jar \
      --spring.flyway.enabled=false \
      --spring.jpa.hibernate.ddl-auto=none \
      --spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      --spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      --app.health.enabled=false \
 && chown linklens:linklens app.jsa
USER linklens

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=180s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT:-8080}/actuator/health/liveness" >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app.jsa", "-jar", "app.jar"]

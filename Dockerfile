# syntax=docker/dockerfile:1
#
# Multi-stage build: a JDK image compiles the jar, a much smaller JRE image runs it.
# Build:  docker build -t payments-ledger .
# Run:    see docker-compose.yml / docker-compose.core.yml

# ---------- Stage 1: build ----------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy only the build definition first. Docker caches each layer, so as long as pom.xml does
# not change, the (slow) dependency download below is reused on the next build.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:resolve dependency:resolve-plugins

COPY src/ src/
# Tests run in CI with Testcontainers (they need Docker); the image build skips them.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && cp target/payments-ledger-*.jar app.jar

# Split the fat jar into layers (dependencies change rarely, our classes change often), so a
# code change only rebuilds and re-pushes the small "application" layer.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------- Stage 2: run ------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Never run as root inside the container.
RUN groupadd --system spring && useradd --system --gid spring --uid 1001 spring

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER spring
EXPOSE 8080

# MaxRAMPercentage sizes the heap from the container's memory limit instead of the host's.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

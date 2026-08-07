# Production backend image for Kitiva.
# Multi-stage: a Maven build stage compiles the Spring Boot fat jar, the run stage ships only a slim JRE + the jar.
# Build from the repo root with:  docker build -t kitiva-backend ./backend
#
# Inside the container Maven resolves from Maven Central directly (the host's corporate-mirror settings.xml is a
# host-only workaround and is never copied in), so no custom settings file is needed here.

# --- Build stage -----------------------------------------------------------------------------------------------
# temurin-17 matches the pom's java.version and is already pulled for the dev compose (no extra image fetch).
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# Package the app. -DskipTests skips test COMPILATION, so test-scope deps (JUnit, Mockito, AssertJ) are never
# fetched — fewer downloads and a smaller failure surface. Tests run on the host / CI (see the build-and-test
# memory), not in the image build.
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests clean package

# --- Run stage -------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS run
# curl is only for the container HEALTHCHECK below; install it, drop the apt lists, and add a non-root user.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 -m -d /home/app app
WORKDIR /app
COPY --from=build /build/target/kitiva-backend-*.jar app.jar

# The prod Spring profile is LOAD-BEARING and baked in on purpose: it is the only thing that flips ddl-auto to a
# non-destructive strategy, disables the dev/admin endpoints, makes the session cookie Secure, and requires an
# explicit CORS origin. Without it the base profile wins (ddl-auto=create -> wipes the DB on every restart). Bake
# it into the image so a forgotten env var on a PaaS dashboard can never silently downgrade to the unsafe defaults.
ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_OPTS=""
EXPOSE 8080
# Health/readiness for the host + load balancer (Sprint 10.85). start-period covers JVM boot + Flyway + the seed.
HEALTHCHECK --interval=30s --timeout=5s --start-period=75s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
USER app
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

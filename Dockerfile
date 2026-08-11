# ---- Build stage ----
# Builds a self-contained distribution using the Gradle `application` plugin.
# Network is only needed here (at image build time) to fetch dependencies.
FROM gradle:8.7-jdk11 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src ./src
RUN gradle --no-daemon installDist

# ---- Runtime stage ----
# The running container needs NO internet: everything is baked in.
FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /app/build/install/fake-stripe ./

# HOST binds all interfaces *inside the container* so the published port works.
# That is not the same as exposing it on your machine — compose publishes the
# controller port on host loopback only. Direct (non-Docker) runs default to
# loopback for both listeners.
ENV PORT=12111 \
    HOST=0.0.0.0 \
    CONTROL_PORT=12112 \
    CONTROL_HOST=127.0.0.1 \
    FAKE_STRIPE_SEED=1 \
    FAKE_STRIPE_DATA=/data/state.json

# State snapshot lives here so it survives container restarts.
VOLUME ["/data"]
EXPOSE 12111 12112

# Run unprivileged. /data is the only path that needs to be writable at runtime.
RUN useradd --system --uid 10001 --create-home --home-dir /home/fakestripe fakestripe \
    && mkdir -p /data \
    && chown -R fakestripe:fakestripe /data /app
USER 10001

ENTRYPOINT ["./bin/fake-stripe"]

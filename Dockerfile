# ---- Build stage ----
# Builds a self-contained distribution using the Gradle `application` plugin.
# Network is only needed here (at image build time) to fetch dependencies.
ARG SOURCE_DATE_EPOCH

FROM gradle:8.7-jdk11@sha256:8942456a1b0a1d3ab52fc8ebb248f13a308aac6f40423d2e5537553a69c5c7f8 AS build
ARG SOURCE_DATE_EPOCH
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --no-daemon --build-cache installDist \
    && mkdir -p build/runtime-data \
    && find build/install/fake-stripe build/runtime-data \
        -exec touch --date="@${SOURCE_DATE_EPOCH}" {} +

# ---- Runtime stage ----
# The running container needs NO internet: everything is baked in.
FROM eclipse-temurin:25-jre@sha256:a214efa3200af4b657e41935799aa12d7aee3336fdb42eb505a0948f6ecdd983
WORKDIR /app
COPY --chown=10001:10001 --from=build /app/build/install/fake-stripe ./
COPY --chown=10001:10001 --from=build /app/build/runtime-data/ /data/

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

# Run unprivileged. The fixed numeric identity avoids mutable OS-account files, while
# COPY --chown above makes /app and /data usable without a root setup step.
USER 10001

ENTRYPOINT ["./bin/fake-stripe"]

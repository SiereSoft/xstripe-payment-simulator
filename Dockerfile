# ---- Build stage ----
# Builds a self-contained distribution using the Gradle `application` plugin.
# Network is only needed here (at image build time) to fetch dependencies.
ARG SOURCE_DATE_EPOCH

FROM gradle:8.13-jdk11@sha256:a24382233b41e118017ff024190f308048d3b01561f6964e86e8c781eae31be3 AS build
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
FROM eclipse-temurin:11-jre@sha256:49328316354e3d19046a08cc4a9b4aa50a07c6636f6bd5d1e7f7e11fc2731fa3
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

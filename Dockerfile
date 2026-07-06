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

ENV PORT=12111 \
    HOST=0.0.0.0 \
    FAKE_STRIPE_SEED=1 \
    FAKE_STRIPE_DATA=/data/state.json

# State snapshot lives here so it survives container restarts.
VOLUME ["/data"]
EXPOSE 12111

ENTRYPOINT ["./bin/fake-stripe"]

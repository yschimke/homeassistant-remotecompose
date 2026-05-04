# Multi-stage build:
#   1) builder: gradle wrapper + JDK 17 (project's toolchain target;
#      the wrapper downloads its own Gradle, so the daemon is whatever
#      `./gradlew` pins). None of `:ha-model` / `:ha-client` /
#      `:addon-server` apply the Metro plugin, so we don't need JDK 21+.
#   2) runtime: distroless JRE 21 — smallest reasonable surface.
#
# We don't use HA's `homeassistant/<arch>-base` image because we want a
# vanilla JRE — the supervisor doesn't require the bashio base for plain
# `application` startup. If we ever need bashio (for `/data/options.json`
# parsing) we can switch and run `run.sh` instead.
ARG BUILD_FROM=eclipse-temurin:25.0.3_9-jdk-jammy
ARG RUN_FROM=gcr.io/distroless/java21-debian12:nonroot

FROM ${BUILD_FROM} AS build
WORKDIR /src
# Copy the wrapper + minimal gradle config first, so dependency resolution
# can be cached across source-only edits.
COPY gradlew /src/gradlew
COPY gradle /src/gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties /src/
COPY ha-model /src/ha-model
COPY ha-client /src/ha-client
COPY addon-server /src/addon-server
RUN chmod +x /src/gradlew && \
    /src/gradlew --no-daemon :addon-server:installDist \
      -x :addon-server:test

FROM ${RUN_FROM}
WORKDIR /app
COPY --from=build /src/addon-server/build/install/addon-server /app
EXPOSE 8099
ENV LOG_LEVEL=info
ENTRYPOINT ["/app/bin/addon-server"]

# Multi-stage build:
#   1) builder: JDK 21 (the project's toolchain and Gradle daemon JVM, see
#      gradle/gradle-daemon-jvm.properties) + the Gradle wrapper. Only the
#      add-on server and the two modules it depends on are copied, plus the
#      `build-logic` included build that supplies `harc.base-conventions`;
#      `-PaddonServerOnly=true` tells settings.gradle.kts to include just
#      those projects. ha-model/ha-client declare an Android target, but their
#      JVM targets build without an Android SDK, so the builder needs none.
#   2) runtime: distroless JRE 21 — smallest reasonable surface.
#
# Build from the repository root:
#   docker build -f addon-server/addon/Dockerfile .
#
# We don't use HA's `homeassistant/<arch>-base` image because we want a
# vanilla JRE — the supervisor doesn't require the bashio base for plain
# `application` startup. If we ever need bashio (for `/data/options.json`
# parsing) we can switch and run `run.sh` instead.
ARG BUILD_FROM=eclipse-temurin:21-jdk-jammy
ARG RUN_FROM=gcr.io/distroless/java21-debian12:nonroot

FROM ${BUILD_FROM} AS build
WORKDIR /src
# Copy the wrapper + minimal gradle config first, so dependency resolution
# can be cached across source-only edits.
COPY gradlew /src/gradlew
COPY gradle /src/gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties /src/
COPY build-logic /src/build-logic
COPY ha-model /src/ha-model
COPY ha-client /src/ha-client
COPY addon-server /src/addon-server
RUN chmod +x /src/gradlew && \
    /src/gradlew --no-daemon --no-configuration-cache \
      -PaddonServerOnly=true \
      :addon-server:installDist -x :addon-server:test

FROM ${RUN_FROM}
WORKDIR /app
COPY --from=build /src/addon-server/build/install/addon-server /app
EXPOSE 8099
ENV LOG_LEVEL=info
ENTRYPOINT ["/app/bin/addon-server"]

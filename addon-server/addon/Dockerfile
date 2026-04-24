# Multi-stage build:
#   1) gradle builder produces a `installDist` tarball
#   2) distroless JRE serves it with the smallest reasonable runtime surface
#
# We don't use HA's `homeassistant/<arch>-base` image because we want a
# vanilla JRE — the supervisor doesn't require the bashio base for plain
# `application` startup. If we ever need bashio (for `/data/options.json`
# parsing) we can switch and run `run.sh` instead.
ARG BUILD_FROM=gradle:8-jdk21
ARG RUN_FROM=gcr.io/distroless/java21-debian12:nonroot

FROM ${BUILD_FROM} AS build
WORKDIR /src
# Copy only what we need to resolve the build cache before copying sources,
# so dependency resolution can be cached across edits.
COPY settings.gradle.kts build.gradle.kts gradle.properties /src/
COPY gradle /src/gradle
COPY ha-model /src/ha-model
COPY ha-client /src/ha-client
COPY addon-server /src/addon-server
RUN gradle --no-daemon :addon-server:installDist

FROM ${RUN_FROM}
WORKDIR /app
COPY --from=build /src/addon-server/build/install/addon-server /app
EXPOSE 8099
ENV LOG_LEVEL=info
ENTRYPOINT ["/app/bin/addon-server"]

# The card design server with its library baked in, and UI Builder bundled beside it. Build the
# server and the library first — generating the library needs Android (Robolectric), so it doesn't
# run in this image — then build from the repo root:
#
#   ./gradlew :card-designs:generateCardDesigns :design-server:installDist :app-ui:wasmJsBrowserDistribution
#   docker build -f design-server/Dockerfile -t card-design-server .
#   docker run -p 8080:8080 -p 8724:8724 -v card-designs-data:/data card-design-server
#
# - Templates: http://<host>:8080/ (point the app's design server setting at
#   http://<host>:8080/designs/v1).
# - UI Builder: the server prints an edit link at startup; open it to seed UI Builder designs from
#   card schemas. Set UI_BUILDER_TOKEN for a stable link, UI_BUILDER_BROWSER_URL when the builder
#   isn't at http://localhost:8724 for the browser, and UI_BUILDER_BINARY= (empty) to leave it out.
# - A public server: sign people in with GitHub (GITHUB_CLIENT_ID, …, UI_BUILDER_GITHUB_CLIENT_ID,
#   …); see design-server/README.md, which also covers deploying at ha.coo.ee.
# - Another library: mount it and set DESIGN_LIBRARY.
ARG UI_BUILDER_VERSION=3.142.0

FROM eclipse-temurin:21-jre AS ui-builder
ARG UI_BUILDER_VERSION
ADD https://github.com/yschimke/compose-preview-server/releases/download/v${UI_BUILDER_VERSION}/compose-preview-server-${UI_BUILDER_VERSION}.tar.gz /tmp/ui-builder.tar.gz
RUN mkdir -p /opt && tar xzf /tmp/ui-builder.tar.gz -C /opt \
  && mv /opt/compose-preview-server-${UI_BUILDER_VERSION} /opt/compose-preview-server

# UI Builder's launcher is a shell script, so a JRE with a shell rather than distroless. Its
# renderer (Skiko) loads libGL and fontconfig even when it draws offscreen, for PNG/SVG export.
FROM eclipse-temurin:21-jre
RUN apt-get update \
  && apt-get install -y --no-install-recommends libgl1 libfontconfig1 fonts-dejavu-core \
  && rm -rf /var/lib/apt/lists/*
RUN useradd --create-home --home-dir /data --uid 10001 designs
COPY --from=ui-builder /opt/compose-preview-server /opt/compose-preview-server
COPY design-server/build/install/design-server/lib /app/lib
COPY card-designs/build/card-designs /library
COPY design-server/catalogs.json /app/catalogs.json
COPY app-ui/build/dist/wasmJs/productionExecutable /app/wasm
ENV DESIGN_LIBRARY=/library \
    PORT=8080 \
    UI_BUILDER_BINARY=/opt/compose-preview-server/bin/compose-preview-server \
    UI_BUILDER_WEB_DIR=/opt/compose-preview-server/ui-builder \
    UI_BUILDER_HOST=0.0.0.0 \
    UI_BUILDER_PORT=8724 \
    UI_BUILDER_STATE_DIR=/data/ui-builder \
    USER_DESIGNS_DIR=/data/user-designs \
    UI_BUILDER_ARGS="--catalogs-file /app/catalogs.json --catalog-cache-dir /data/catalog-cache --ui-builder-catalogs remote-m3,ha-cards,lovelace"
USER designs
WORKDIR /data
VOLUME /data
EXPOSE 8080 8724
ENTRYPOINT ["java", "-cp", "/app/lib/*", "ee.schimke.ha.designserver.MainKt"]

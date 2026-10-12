# The card design server with its library baked in. Build both first — generating the library
# needs Android (Robolectric), so it doesn't run in this image — then build from the repo root:
#
#   ./gradlew :card-designs:generateCardDesigns :design-server:installDist
#   docker build -f design-server/Dockerfile -t card-design-server .
#   docker run -p 8080:8080 card-design-server
#
# Point the app's design server setting at http://<host>:8080/designs/v1; browse the templates at
# http://<host>:8080/. To serve another library, mount it and set DESIGN_LIBRARY.
ARG RUN_FROM=gcr.io/distroless/java21-debian12:nonroot
FROM ${RUN_FROM}
WORKDIR /app
COPY design-server/build/install/design-server/lib /app/lib
COPY card-designs/build/card-designs /library
ENV DESIGN_LIBRARY=/library PORT=8080
EXPOSE 8080
# Distroless has no shell for the Gradle start script; run the JVM directly.
ENTRYPOINT ["java", "-cp", "/app/lib/*", "ee.schimke.ha.designserver.MainKt"]

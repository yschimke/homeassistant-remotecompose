# The design server image (design-server/Dockerfile: the server, its library and the bundled UI
# Builder) with nginx in front, which puts both on the one ingress port. See README.md.
ARG BUILD_FROM=ghcr.io/yschimke/ha-card-design-server:latest
FROM ${BUILD_FROM}

USER root
RUN apt-get update \
  && apt-get install -y --no-install-recommends nginx jq \
  && rm -rf /var/lib/apt/lists/* /etc/nginx/sites-enabled/default

COPY nginx.conf /etc/nginx/card-designs.conf.template
COPY ingress.js /etc/nginx/ingress.js
COPY run.sh /run.sh
RUN chmod +x /run.sh

# The add-on's own volume, not the image's.
ENV LIBRARY_STORE=/data/library \
    UI_BUILDER_STATE_DIR=/data/ui-builder \
    USER_DESIGNS_DIR=/data/user-designs \
    USAGE_DIR=/data/usage
WORKDIR /data
ENTRYPOINT ["/run.sh"]

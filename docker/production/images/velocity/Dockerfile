FROM eclipse-temurin:21-jre

ARG VELOCITY_VERSION=latest
ARG USER_AGENT="hypixel-docker/2.0 (https://example.net)"

RUN apt-get update \
    && apt-get install -y --no-install-recommends bash curl jq ca-certificates \
    && mkdir -p /server/plugins/hypixelproxy /bootstrap/proxy \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /server

COPY install-velocity.sh /usr/local/bin/install-velocity.sh
RUN chmod +x /usr/local/bin/install-velocity.sh

COPY entrypoint.sh /usr/local/bin/velocity-entrypoint.sh
RUN chmod +x /usr/local/bin/velocity-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/velocity-entrypoint.sh"]

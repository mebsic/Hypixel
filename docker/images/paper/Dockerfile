FROM eclipse-temurin:8-jre

ARG PAPER_VERSION=1.8.8
ARG USER_AGENT="hypixel-docker/2.0 (https://example.com)"

RUN apt-get update \
    && apt-get install -y --no-install-recommends bash curl jq unzip ca-certificates \
    && mkdir -p /data/plugins/Hypixel \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /server

COPY install-paper.sh /usr/local/bin/install-paper.sh
RUN chmod +x /usr/local/bin/install-paper.sh \
    && PAPER_VERSION="${PAPER_VERSION}" USER_AGENT="${USER_AGENT}" /usr/local/bin/install-paper.sh

COPY entrypoint.sh /usr/local/bin/paper-entrypoint.sh
COPY restart.sh /usr/local/bin/paper-restart.sh
RUN chmod +x /usr/local/bin/paper-entrypoint.sh
RUN chmod +x /usr/local/bin/paper-restart.sh

ENTRYPOINT ["/usr/local/bin/paper-entrypoint.sh"]

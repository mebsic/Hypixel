#!/usr/bin/env bash
set -euo pipefail

CONFIG_SOURCE="${CONFIG_SOURCE:-/bootstrap/config.json}"

mkdir -p /server/plugins/hypixelproxy

apply_network_config_overrides() {
  local config_file="$1"
  local redis_password_set="false"
  local tmp_file=""

  [[ -f "${config_file}" ]] || return 0

  if [[ "${REDIS_PASSWORD+x}" == "x" ]]; then
    redis_password_set="true"
  fi

  tmp_file="$(mktemp)"
  if jq \
    --arg mongo_uri "${MONGO_URI:-}" \
    --arg mongo_database "${MONGO_DATABASE:-}" \
    --arg redis_host "${REDIS_HOST:-}" \
    --arg redis_port "${REDIS_PORT:-}" \
    --arg redis_password "${REDIS_PASSWORD:-}" \
    --argjson redis_password_set "${redis_password_set}" \
    --arg redis_database "${REDIS_DATABASE:-}" '
      .mongo = (.mongo // {}) |
      .redis = (.redis // {}) |
      .mongo.uri = (if $mongo_uri != "" then $mongo_uri else .mongo.uri end) |
      .mongo.database = (if $mongo_database != "" then $mongo_database else .mongo.database end) |
      .redis.host = (if $redis_host != "" then $redis_host else .redis.host end) |
      .redis.port = (if $redis_port != "" then ($redis_port | tonumber) else .redis.port end) |
      .redis.password = (if $redis_password_set then $redis_password else .redis.password end) |
      .redis.database = (if $redis_database != "" then ($redis_database | tonumber) else .redis.database end)
    ' "${config_file}" > "${tmp_file}"; then
    mv "${tmp_file}" "${config_file}"
  else
    echo "Warning: failed to apply network overrides to ${config_file}" >&2
    rm -f "${tmp_file}"
  fi
}

if [[ -f "${CONFIG_SOURCE}" ]]; then
  cp "${CONFIG_SOURCE}" /server/plugins/hypixelproxy/config.json
  apply_network_config_overrides /server/plugins/hypixelproxy/config.json
fi

if [[ -f /bootstrap/proxy/server-icon.png ]]; then
  cp /bootstrap/proxy/server-icon.png /server/server-icon.png
  cp /bootstrap/proxy/server-icon.png /server/plugins/hypixelproxy/server-icon.png
fi

if [[ ! -f /server/velocity.toml ]]; then
  cat > /server/velocity.toml <<'TOML'
bind = "0.0.0.0:25565"
motd = "A Hypixel Network"
show-max-players = 200
online-mode = false
force-key-authentication = false
prevent-client-proxy-connections = false
player-info-forwarding-mode = "legacy"
forwarding-secret-file = "forwarding.secret"
announce-forge = false
kick-existing-players = false
ping-passthrough = "disabled"
enable-player-address-logging = true

[servers]
placeholder = "127.0.0.1:1"
try = ["placeholder"]

config-version = "2.7"

[forced-hosts]

[advanced]
bungee-plugin-message-channel = true
TOML
fi

exec java ${JAVA_OPTS:-"-Xms256M -Xmx512M"} -jar /server/velocity.jar

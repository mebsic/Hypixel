#!/usr/bin/env bash
set -euo pipefail

DATA_DIR="${DATA_DIR:-/data}"
MAP_ROOT="${MAP_ROOT:-/maps}"
GAME_TYPE="${GAME_TYPE:-murdermystery}"
MAP_NAME="${MAP_NAME:-}"
WORLD_NAME_RAW="${WORLD_NAME:-}"
WORLD_NAME="${WORLD_NAME_RAW:-world}"
SERVER_KIND="${SERVER_KIND:-game}"
AUTO_WORLD_NAME_FROM_MAP="false"
if [[ -z "${WORLD_NAME_RAW}" || ( "${WORLD_NAME_RAW}" == "world" && "${SERVER_KIND,,}" != "build" ) ]]; then
  AUTO_WORLD_NAME_FROM_MAP="true"
fi
FORCE_MAP_COPY="${FORCE_MAP_COPY:-false}"
MAP_APPLY_MODE="${MAP_APPLY_MODE:-copy}"
LEVEL_TYPE="${LEVEL_TYPE:-DEFAULT}"
GENERATOR_SETTINGS="${GENERATOR_SETTINGS:-}"
LEVEL_NAME="${WORLD_NAME}"
SERVER_MAX_PLAYERS="${SERVER_MAX_PLAYERS:-}"
HOST_TOKEN="${HOSTNAME:-paper}"
CONFIG_SOURCE="${CONFIG_SOURCE:-/bootstrap/config.json}"
RESTART_SCRIPT_PATH="${RESTART_SCRIPT_PATH:-/usr/local/bin/paper-restart.sh}"
PORT_ALLOC_ROOT="${PORT_ALLOC_ROOT:-/var/lib/hycopy-port}"
PORT_ALLOC_MIN="${PORT_ALLOC_MIN:-25590}"
PORT_ALLOC_MAX="${PORT_ALLOC_MAX:-29999}"
USER_AGENT="${USER_AGENT:-hycopy-docker/2.0 (https://example.net)}"
PLUGIN_SOURCE_DIR="${PLUGIN_SOURCE_DIR:-/bootstrap/plugins}"

mkdir -p "${DATA_DIR}" "${DATA_DIR}/plugins" "${DATA_DIR}/plugins/Hycopy"

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
  cp "${CONFIG_SOURCE}" "${DATA_DIR}/plugins/Hycopy/config.json"
  apply_network_config_overrides "${DATA_DIR}/plugins/Hycopy/config.json"
fi

spigot_file="${DATA_DIR}/spigot.yml"
if [[ ! -f "${spigot_file}" ]]; then
  cat > "${spigot_file}" <<EOF
settings:
  restart-script: ${RESTART_SCRIPT_PATH}
  bungeecord: true
EOF
else
  tmp_file="$(mktemp)"
  awk -v script="${RESTART_SCRIPT_PATH}" '
    BEGIN { in_settings = 0; restart_inserted = 0; bungee_inserted = 0; }
    {
      line = $0;
      if (line ~ /^[[:space:]]*restart-script:[[:space:]]*/) {
        print "  restart-script: " script;
        restart_inserted = 1;
        next;
      }
      if (line ~ /^[[:space:]]*bungeecord:[[:space:]]*/) {
        print "  bungeecord: true";
        bungee_inserted = 1;
        next;
      }
      print line;
      if (line ~ /^settings:[[:space:]]*$/) {
        in_settings = 1;
        next;
      }
      if (in_settings && line ~ /^[^[:space:]]/ ) {
        if (!restart_inserted) {
          print "  restart-script: " script;
          restart_inserted = 1;
        }
        if (!bungee_inserted) {
          print "  bungeecord: true";
          bungee_inserted = 1;
        }
        in_settings = 0;
      }
    }
    END {
      if (in_settings) {
        if (!restart_inserted) {
          print "  restart-script: " script;
          restart_inserted = 1;
        }
        if (!bungee_inserted) {
          print "  bungeecord: true";
          bungee_inserted = 1;
        }
      }
      if (!restart_inserted || !bungee_inserted) {
        print "";
        print "settings:";
        if (!restart_inserted) {
          print "  restart-script: " script;
        }
        if (!bungee_inserted) {
          print "  bungeecord: true";
        }
      }
    }
  ' "${spigot_file}" > "${tmp_file}"
  mv "${tmp_file}" "${spigot_file}"
fi

bukkit_file="${DATA_DIR}/bukkit.yml"
if [[ ! -f "${bukkit_file}" ]]; then
  cat > "${bukkit_file}" <<'EOF'
settings:
  allow-end: false
EOF
else
  tmp_file="$(mktemp)"
  awk '
    BEGIN { in_settings = 0; allow_end_inserted = 0; }
    {
      line = $0;
      if (line ~ /^[[:space:]]*allow-end:[[:space:]]*/) {
        print "  allow-end: false";
        allow_end_inserted = 1;
        next;
      }
      print line;
      if (line ~ /^settings:[[:space:]]*$/) {
        in_settings = 1;
        next;
      }
      if (in_settings && line ~ /^[^[:space:]]/) {
        if (!allow_end_inserted) {
          print "  allow-end: false";
          allow_end_inserted = 1;
        }
        in_settings = 0;
      }
    }
    END {
      if (in_settings && !allow_end_inserted) {
        print "  allow-end: false";
        allow_end_inserted = 1;
      }
      if (!allow_end_inserted) {
        print "";
        print "settings:";
        print "  allow-end: false";
      }
    }
  ' "${bukkit_file}" > "${tmp_file}"
  mv "${tmp_file}" "${bukkit_file}"
fi

is_true() {
  local value="${1:-}"
  local normalized="${value,,}"
  [[ "${normalized}" == "true" || "${normalized}" == "1" || "${normalized}" == "yes" ]]
}

stage_plugin() {
  local file_name="$1"
  local runtime_target="${DATA_DIR}/plugins/${file_name}"
  local source_target=""

  if [[ -n "${PLUGIN_SOURCE_DIR}" && -d "${PLUGIN_SOURCE_DIR}" ]]; then
    source_target="${PLUGIN_SOURCE_DIR}/${file_name}"

    if [[ -d "${source_target}" ]]; then
      echo "[bootstrap] Fixing plugin path type mismatch for ${file_name}."
      rm -rf "${source_target}"
    fi

    if [[ -f "${source_target}" ]]; then
      cp -f "${source_target}" "${runtime_target}"
    fi
  fi

  if [[ -s "${runtime_target}" ]]; then
    return 0
  fi

  echo "[bootstrap] Required plugin ${file_name} not found in ${PLUGIN_SOURCE_DIR} or ${runtime_target}." >&2
  return 1
}

stage_required_runtime_plugins() {
  local required_plugins=("Hycopy.jar")
  local plugin_file=""

  case "${SERVER_KIND,,}" in
    build)
      required_plugins+=("HycopyBuild.jar")
      ;;
    hub|game)
      required_plugins+=("MurderMystery.jar")
      ;;
  esac

  for plugin_file in "${required_plugins[@]}"; do
    stage_plugin "${plugin_file}"
  done
}

normalize_map_dir_name() {
  local value="${1:-}"
  printf '%s' "${value}" | tr '[:upper:]' '[:lower:]' | tr -cd '[:alnum:]'
}

is_hub_map_name() {
  local normalized_name
  normalized_name="$(normalize_map_dir_name "${1:-}")"
  [[ -n "${normalized_name}" && "${normalized_name}" == *hub* ]]
}

is_placeholder_map_name() {
  local normalized_name
  normalized_name="$(normalize_map_dir_name "${1:-}")"
  [[ -n "${normalized_name}" && (
    "${normalized_name}" == "world" ||
    "${normalized_name}" == "worldnether" ||
    "${normalized_name}" == "worldtheend" ||
    "${normalized_name}" == "default"
  ) ]]
}

read_existing_level_name() {
  local server_properties_file="${DATA_DIR}/server.properties"
  local level_name=""
  if [[ ! -f "${server_properties_file}" ]]; then
    return 0
  fi
  level_name="$(awk -F= '/^[[:space:]]*level-name[[:space:]]*=/{print $2; exit}' "${server_properties_file}" 2>/dev/null || true)"
  level_name="${level_name//$'\r'/}"
  level_name="${level_name#"${level_name%%[![:space:]]*}"}"
  level_name="${level_name%"${level_name##*[![:space:]]}"}"
  if [[ -n "${level_name}" ]]; then
    printf '%s' "${level_name}"
  fi
}

resolve_map_name_from_data_world() {
  local world_dir="$1"
  local marker_file="${world_dir}/.hycopy-map-source"
  local raw=""
  local map_name=""
  local link_target=""

  if [[ -f "${marker_file}" ]]; then
    raw="$(cat "${marker_file}" 2>/dev/null || true)"
    raw="${raw//$'\r'/}"
    raw="${raw//$'\n'/}"
    if [[ -n "${raw}" ]]; then
      map_name="${raw##*/}"
      if [[ -n "${map_name}" ]] && ! is_placeholder_map_name "${map_name}"; then
        printf '%s' "${map_name}"
        return 0
      fi
    fi
  fi

  if [[ -L "${world_dir}" ]]; then
    link_target="$(readlink "${world_dir}" 2>/dev/null || true)"
    if [[ -n "${link_target}" ]]; then
      map_name="$(basename "${link_target}")"
      if [[ -n "${map_name}" ]] && ! is_placeholder_map_name "${map_name}"; then
        printf '%s' "${map_name}"
        return 0
      fi
    fi
  fi
}

resolve_map_source_dir() {
  local map_game_root="$1"
  local requested_map_name="$2"
  local server_kind="$3"
  local direct_path="${map_game_root}/${requested_map_name}"
  local requested_normalized
  local prefer_hub="false"
  local saved_nullglob
  local dir base_name normalized_name
  local normalized_exact=""
  local normalized_contains=""
  local hub_contains=""
  local first_any=""
  local first_hub=""
  local first_non_hub=""

  if [[ -n "${requested_map_name}" && -d "${direct_path}" ]]; then
    printf '%s' "${direct_path}"
    return 0
  fi

  if [[ ! -d "${map_game_root}" ]]; then
    return 0
  fi

  requested_normalized="$(normalize_map_dir_name "${requested_map_name}")"
  if [[ "${server_kind,,}" == "hub" || "${requested_normalized}" == *hub* ]]; then
    prefer_hub="true"
  fi

  if shopt -q nullglob; then
    saved_nullglob="on"
  else
    saved_nullglob="off"
    shopt -s nullglob
  fi

  for dir in "${map_game_root}"/*; do
    [[ -d "${dir}" ]] || continue
    base_name="$(basename "${dir}")"
    normalized_name="$(normalize_map_dir_name "${base_name}")"

    if [[ -z "${first_any}" ]]; then
      first_any="${dir}"
    fi
    if [[ "${normalized_name}" == *hub* ]]; then
      if [[ -z "${first_hub}" ]]; then
        first_hub="${dir}"
      fi
    elif [[ -z "${first_non_hub}" ]]; then
      first_non_hub="${dir}"
    fi

    if [[ -n "${requested_normalized}" && "${normalized_name}" == "${requested_normalized}" ]]; then
      normalized_exact="${dir}"
      break
    fi
    if [[ -z "${normalized_contains}" && -n "${requested_normalized}" && "${normalized_name}" == *"${requested_normalized}"* ]]; then
      normalized_contains="${dir}"
    fi
    if [[ "${prefer_hub}" == "true" && -z "${hub_contains}" && "${normalized_name}" == *hub* ]]; then
      hub_contains="${dir}"
    fi
  done

  if [[ "${saved_nullglob}" == "off" ]]; then
    shopt -u nullglob
  fi

  if [[ -n "${normalized_exact}" ]]; then
    printf '%s' "${normalized_exact}"
    return 0
  fi
  if [[ -n "${normalized_contains}" ]]; then
    printf '%s' "${normalized_contains}"
    return 0
  fi
  if [[ -z "${requested_normalized}" ]]; then
    if [[ "${prefer_hub}" == "true" ]]; then
      if [[ -n "${first_hub}" ]]; then
        printf '%s' "${first_hub}"
        return 0
      fi
    else
      if [[ -n "${first_non_hub}" ]]; then
        printf '%s' "${first_non_hub}"
        return 0
      fi
    fi
    if [[ -n "${first_any}" ]]; then
      printf '%s' "${first_any}"
      return 0
    fi
  fi
  if [[ -n "${hub_contains}" ]]; then
    printf '%s' "${hub_contains}"
  fi
}

resolve_world_template_dir() {
  local source_map="$1"
  local saved_nullglob
  local child candidate=""

  if [[ ! -d "${source_map}" ]]; then
    return 0
  fi

  if [[ -f "${source_map}/level.dat" || -d "${source_map}/region" ]]; then
    printf '%s' "${source_map}"
    return 0
  fi

  if shopt -q nullglob; then
    saved_nullglob="on"
  else
    saved_nullglob="off"
    shopt -s nullglob
  fi

  for child in "${source_map}"/*; do
    [[ -d "${child}" ]] || continue
    if [[ -n "${candidate}" ]]; then
      candidate=""
      break
    fi
    candidate="${child}"
  done

  if [[ "${saved_nullglob}" == "off" ]]; then
    shopt -u nullglob
  fi

  if [[ -n "${candidate}" && ( -f "${candidate}/level.dat" || -d "${candidate}/region" ) ]]; then
    printf '%s' "${candidate}"
  fi
}

copy_world_template_if_needed() {
  local template_dir="$1"
  local target_world="$2"
  local force_copy="$3"
  local map_identifier="$4"
  local marker_file="${target_world}/.hycopy-map-source"
  local existing_marker=""
  local should_copy="false"
  local target_label

  target_label="$(basename "${target_world}")"

  if [[ ! -d "${template_dir}" ]]; then
    return 1
  fi

  if is_true "${force_copy}"; then
    should_copy="true"
  fi

  if [[ "${should_copy}" != "true" ]]; then
    if [[ ! -f "${target_world}/level.dat" && ! -d "${target_world}/region" ]]; then
      should_copy="true"
    fi
  fi

  if [[ "${should_copy}" != "true" ]]; then
    existing_marker="$(cat "${marker_file}" 2>/dev/null || true)"
    if [[ "${existing_marker}" != "${map_identifier}" ]]; then
      should_copy="true"
    fi
  fi

  if [[ "${should_copy}" != "true" ]]; then
    echo "[bootstrap] Keeping existing ${target_label} world (set FORCE_MAP_COPY=true to refresh from templates)."
    return 0
  fi

  rm -rf "${target_world}"
  mkdir -p "${target_world}"
  cp -a "${template_dir}/." "${target_world}/"
  rm -f "${target_world}/session.lock"
  printf '%s' "${map_identifier}" > "${marker_file}"
  echo "[bootstrap] Copied world template ${map_identifier} into ${target_world}."
  return 0
}

link_world_template_if_needed() {
  local template_dir="$1"
  local target_world="$2"
  local force_copy="$3"
  local map_identifier="$4"
  local current_target=""
  local target_label

  target_label="$(basename "${target_world}")"

  if [[ ! -d "${template_dir}" ]]; then
    return 1
  fi

  if [[ -L "${target_world}" ]]; then
    current_target="$(readlink "${target_world}" 2>/dev/null || true)"
    if [[ "${current_target}" == "${template_dir}" ]] && ! is_true "${force_copy}"; then
      echo "[bootstrap] Keeping existing ${target_label} world link to ${map_identifier}."
      return 0
    fi
  elif [[ -d "${target_world}" ]] && ! is_true "${force_copy}"; then
    echo "[bootstrap] Replacing existing ${target_label} world directory with direct map link (${map_identifier})."
  fi

  rm -rf "${target_world}"
  ln -s "${template_dir}" "${target_world}"
  echo "[bootstrap] Linked world template ${map_identifier} to ${target_world}."
  return 0
}

apply_world_template_if_needed() {
  local template_dir="$1"
  local target_world="$2"
  local force_copy="$3"
  local map_identifier="$4"
  local apply_mode="$5"
  local normalized_mode="${apply_mode,,}"

  if [[ "${normalized_mode}" == "link" || "${normalized_mode}" == "symlink" ]]; then
    link_world_template_if_needed "${template_dir}" "${target_world}" "${force_copy}" "${map_identifier}"
    return $?
  fi
  if [[ "${normalized_mode}" != "copy" && -n "${normalized_mode}" ]]; then
    echo "[bootstrap] Unknown MAP_APPLY_MODE=${apply_mode}; falling back to copy mode."
  fi
  copy_world_template_if_needed "${template_dir}" "${target_world}" "${force_copy}" "${map_identifier}"
}

preload_build_world_templates() {
  local map_root="$1"
  local data_dir="$2"
  local force_copy="$3"
  local saved_nullglob
  local game_root map_dir game_key map_name
  local template_dir map_identifier target_world_dir
  local copied_any="false"

  if [[ ! -d "${map_root}" ]]; then
    echo "[bootstrap] Map root not found at ${map_root}; skipping build world preload."
    return 0
  fi

  if shopt -q nullglob; then
    saved_nullglob="on"
  else
    saved_nullglob="off"
    shopt -s nullglob
  fi

  for game_root in "${map_root}"/*; do
    [[ -d "${game_root}" ]] || continue
    game_key="$(basename "${game_root}")"
    for map_dir in "${game_root}"/*; do
      [[ -d "${map_dir}" ]] || continue
      map_name="$(basename "${map_dir}")"
      template_dir="$(resolve_world_template_dir "${map_dir}")"
      if [[ -z "${template_dir}" ]]; then
        echo "[bootstrap] Skipping build preload for ${game_key}/${map_name}: not a world root."
        continue
      fi
      map_identifier="${game_key}/${map_name}"
      target_world_dir="${data_dir}/${map_name}"
      if copy_world_template_if_needed "${template_dir}" "${target_world_dir}" "${force_copy}" "${map_identifier}"; then
        copied_any="true"
      else
        echo "[bootstrap] Failed to preload world template ${map_identifier} into ${target_world_dir}!" >&2
      fi
    done
  done

  if [[ "${saved_nullglob}" == "off" ]]; then
    shopt -u nullglob
  fi

  if [[ "${copied_any}" != "true" ]]; then
    echo "[bootstrap] No world templates preloaded for build server from ${map_root}."
  fi
}

download_build_plugin() {
  local file_name="$1"
  shift || true
  local plugin_urls=("$@")
  local target_file="${DATA_DIR}/plugins/${file_name}"
  local file_base="${file_name%.jar}"
  local existing_candidate=""
  if [[ "${#plugin_urls[@]}" -eq 0 ]]; then
    return 0
  fi
  if [[ -s "${target_file}" ]]; then
    return 0
  fi

  # Accept pre-provisioned version-suffixed jars (e.g. WorldEdit-6.1.9.jar)
  # and normalize them to the expected runtime filename.
  shopt -s nullglob
  for existing_candidate in "${DATA_DIR}/plugins/${file_base}"-*.jar; do
    if [[ -s "${existing_candidate}" ]]; then
      mv -f "${existing_candidate}" "${target_file}"
      shopt -u nullglob
      return 0
    fi
  done
  shopt -u nullglob

  echo "[bootstrap] Downloading ${file_name}..."
  local plugin_url
  for plugin_url in "${plugin_urls[@]}"; do
    if [[ -z "${plugin_url}" ]]; then
      continue
    fi
    rm -f "${target_file}"
    if curl -fL -H "User-Agent: ${USER_AGENT}" -o "${target_file}" "${plugin_url}"; then
      return 0
    fi
    echo "[bootstrap] Failed to download ${file_name} from ${plugin_url}! Trying next source..." >&2
  done
  echo "[bootstrap] Failed to download ${file_name} from all configured sources!" >&2
  return 1
}

resolve_citizens_url() {
  if [[ -n "${CITIZENS_URL:-}" ]]; then
    printf '%s' "${CITIZENS_URL}"
    return 0
  fi
  # Keep a known 1.8-compatible Citizens build by default.
  printf '%s' "https://ci.citizensnpcs.co/job/Citizens2/2924/artifact/dist/target/Citizens-2.0.30-b2924.jar"
}

ensure_citizens_plugin() {
  local kind="${SERVER_KIND,,}"
  local type="${SERVER_TYPE^^}"
  local game_type="${GAME_TYPE,,}"
  local citizens_url=""
  local murder_mystery_server="false"
  if [[ ( "${kind}" == "game" || "${kind}" == "hub" ) && ( "${game_type}" == "murdermystery" || "${type}" == "MURDER_MYSTERY" || "${type}" == "MURDER_MYSTERY_HUB" ) ]]; then
    murder_mystery_server="true"
  fi
  if [[ "${murder_mystery_server}" != "true" ]]; then
    return 0
  fi
  citizens_url="$(resolve_citizens_url)"
  if [[ -z "${citizens_url}" ]]; then
    echo "[bootstrap] Citizens URL is empty; skipping Citizens installation." >&2
    return 1
  fi
  if ! download_build_plugin "Citizens.jar" "${citizens_url}"; then
    echo "[bootstrap] Citizens download failed. Provide CITIZENS_URL or mount /data/plugins/Citizens.jar." >&2
    return 1
  fi
  return 0
}

extract_world_archive() {
  local archive_url="$1"
  local target_world="$2"
  local force_extract="$3"
  local tmp_archive tmp_extract archive_name lowered source_dir top_count first_entry

  if [[ -z "${archive_url}" ]]; then
    return 0
  fi
  if [[ -f "${target_world}/level.dat" ]] && ! is_true "${force_extract}"; then
    echo "[bootstrap] Skipping world archive download because ${WORLD_NAME} already exists."
    return 0
  fi

  tmp_archive="$(mktemp)"
  tmp_extract="$(mktemp -d)"
  archive_name="${archive_url%%\?*}"
  lowered="${archive_name,,}"

  echo "[bootstrap] Downloading world archive from ${archive_url}..."
  if ! curl -fL -H "User-Agent: ${USER_AGENT}" -o "${tmp_archive}" "${archive_url}"; then
    echo "[bootstrap] Failed to download world archive from ${archive_url}!" >&2
    rm -f "${tmp_archive}"
    rm -rf "${tmp_extract}"
    return 1
  fi

  if [[ "${lowered}" == *.zip ]]; then
    unzip -q "${tmp_archive}" -d "${tmp_extract}"
  elif [[ "${lowered}" == *.tar.gz || "${lowered}" == *.tgz ]]; then
    tar -xzf "${tmp_archive}" -C "${tmp_extract}"
  elif [[ "${lowered}" == *.tar ]]; then
    tar -xf "${tmp_archive}" -C "${tmp_extract}"
  else
    if ! unzip -q "${tmp_archive}" -d "${tmp_extract}" 2>/dev/null; then
      tar -xf "${tmp_archive}" -C "${tmp_extract}" 2>/dev/null || {
        echo "[bootstrap] Unsupported world archive format: ${archive_url}" >&2
        rm -f "${tmp_archive}"
        rm -rf "${tmp_extract}"
        return 1
      }
    fi
  fi

  source_dir="${tmp_extract}"
  top_count="$(find "${tmp_extract}" -mindepth 1 -maxdepth 1 | wc -l | tr -d '[:space:]')"
  if [[ "${top_count}" == "1" ]]; then
    first_entry="$(find "${tmp_extract}" -mindepth 1 -maxdepth 1 -print -quit)"
    if [[ -d "${first_entry}" ]]; then
      source_dir="${first_entry}"
    fi
  fi

  if [[ -z "$(find "${source_dir}" -mindepth 1 -print -quit)" ]]; then
    echo "[bootstrap] World archive did not contain any files: ${archive_url}" >&2
    rm -f "${tmp_archive}"
    rm -rf "${tmp_extract}"
    return 1
  fi

  rm -rf "${target_world}"
  mkdir -p "${target_world}"
  cp -a "${source_dir}/." "${target_world}/"
  echo "[bootstrap] Extracted world archive into ${WORLD_NAME}."

  rm -f "${tmp_archive}"
  rm -rf "${tmp_extract}"
}

resolve_map_name_from_mongo() {
  local mongo_uri="${MONGO_URI:-}"
  local mongo_database="${MONGO_DATABASE:-}"
  local core_jar="${DATA_DIR}/plugins/Hycopy.jar"
  local resolved=""

  if [[ -z "${mongo_uri}" && -f "${CONFIG_SOURCE}" ]]; then
    mongo_uri="$(jq -r '.mongo.uri // empty' "${CONFIG_SOURCE}" 2>/dev/null || true)"
  fi
  if [[ -z "${mongo_database}" && -f "${CONFIG_SOURCE}" ]]; then
    mongo_database="$(jq -r '.mongo.database // empty' "${CONFIG_SOURCE}" 2>/dev/null || true)"
  fi
  if [[ -z "${mongo_uri}" || -z "${mongo_database}" ]]; then
    return 0
  fi
  if [[ ! -f "${core_jar}" ]]; then
    return 0
  fi

  resolved="$(
    java -cp "${core_jar}" io.github.mebsic.core.tool.MapWorldResolverCli \
      "${mongo_uri}" "${mongo_database}" "${GAME_TYPE}" "${SERVER_KIND}" 2>/dev/null || true
  )"
  resolved="${resolved//$'\r'/}"
  resolved="${resolved//$'\n'/}"
  if [[ -n "${resolved}" ]]; then
    printf '%s' "${resolved}"
  fi
}

stage_required_runtime_plugins

if [[ -z "${MAP_NAME}" && "${SERVER_KIND,,}" != "build" ]]; then
  resolved_map_name="$(resolve_map_name_from_mongo)"
  if [[ -n "${resolved_map_name}" ]] && ! is_placeholder_map_name "${resolved_map_name}"; then
    MAP_NAME="${resolved_map_name}"
    if [[ "${AUTO_WORLD_NAME_FROM_MAP}" == "true" ]]; then
      WORLD_NAME="${MAP_NAME}"
      LEVEL_NAME="${WORLD_NAME}"
      echo "[bootstrap] Using DB-resolved world name ${WORLD_NAME}."
    fi
    echo "[bootstrap] Resolved map ${GAME_TYPE}/${MAP_NAME} for server kind ${SERVER_KIND}."
  else
    if [[ -n "${resolved_map_name}" ]]; then
      echo "[bootstrap] Ignoring placeholder Mongo map name ${resolved_map_name}; selecting concrete map."
    fi
    existing_level_name="$(read_existing_level_name)"
    data_world_dir="${DATA_DIR}/${WORLD_NAME}"
    if [[ -n "${existing_level_name}" ]]; then
      data_world_dir="${DATA_DIR}/${existing_level_name}"
    fi
    data_map_name="$(resolve_map_name_from_data_world "${data_world_dir}")"
    if [[ -n "${data_map_name}" ]]; then
      MAP_NAME="${data_map_name}"
      if [[ "${AUTO_WORLD_NAME_FROM_MAP}" == "true" ]]; then
        WORLD_NAME="${MAP_NAME}"
        LEVEL_NAME="${WORLD_NAME}"
        echo "[bootstrap] Using data-resolved world name ${WORLD_NAME}."
      fi
      echo "[bootstrap] Resolved map ${GAME_TYPE}/${MAP_NAME} from ${data_world_dir}."
    else
      fallback_source_map="$(resolve_map_source_dir "${MAP_ROOT}/${GAME_TYPE}" "" "${SERVER_KIND}")"
      if [[ -n "${fallback_source_map}" ]]; then
        MAP_NAME="$(basename "${fallback_source_map}")"
        if [[ "${AUTO_WORLD_NAME_FROM_MAP}" == "true" ]]; then
          WORLD_NAME="${MAP_NAME}"
          LEVEL_NAME="${WORLD_NAME}"
        fi
        echo "[bootstrap] Fallback map selection ${GAME_TYPE}/${MAP_NAME} for server kind ${SERVER_KIND}."
      else
        echo "[bootstrap] No map resolved from Mongo/data for ${GAME_TYPE} (${SERVER_KIND}); continuing without map selection."
      fi
    fi
  fi
fi

if [[ "${SERVER_KIND,,}" == "build" ]]; then
  preload_build_world_templates "${MAP_ROOT}" "${DATA_DIR}" "${FORCE_MAP_COPY}"
fi

if [[ -n "${MAP_NAME}" ]]; then
  map_game_root="${MAP_ROOT}/${GAME_TYPE}"
  if [[ "${SERVER_KIND,,}" != "build" ]] && is_placeholder_map_name "${MAP_NAME}"; then
    echo "[bootstrap] MAP_NAME=${MAP_NAME} is a placeholder; selecting a concrete map directory."
    placeholder_source_map="$(resolve_map_source_dir "${map_game_root}" "" "${SERVER_KIND}")"
    if [[ -n "${placeholder_source_map}" ]]; then
      MAP_NAME="$(basename "${placeholder_source_map}")"
    fi
  fi
  source_map="$(resolve_map_source_dir "${map_game_root}" "${MAP_NAME}" "${SERVER_KIND}")"
  if [[ -z "${source_map}" ]]; then
    source_map="${map_game_root}/${MAP_NAME}"
  fi
  if [[ "${SERVER_KIND,,}" == "game" ]]; then
    resolved_map_name="$(basename "${source_map}")"
    if [[ ! -d "${source_map}" ]] || is_hub_map_name "${resolved_map_name}"; then
      fallback_source_map="$(resolve_map_source_dir "${map_game_root}" "" "game")"
      fallback_map_name="$(basename "${fallback_source_map}")"
      if [[ -n "${fallback_source_map}" && -d "${fallback_source_map}" ]] && ! is_hub_map_name "${fallback_map_name}"; then
        if [[ ! -d "${source_map}" ]]; then
          echo "[bootstrap] Requested game map ${GAME_TYPE}/${MAP_NAME} not found; using ${GAME_TYPE}/${fallback_map_name}."
        else
          echo "[bootstrap] Requested game map ${GAME_TYPE}/${MAP_NAME} resolved to hub map ${resolved_map_name}; using ${GAME_TYPE}/${fallback_map_name}."
        fi
        source_map="${fallback_source_map}"
      fi
    fi
  fi
  resolved_map_name="$(basename "${source_map}")"
  if [[ "${resolved_map_name}" != "${MAP_NAME}" && -d "${source_map}" ]]; then
    echo "[bootstrap] Using map directory ${GAME_TYPE}/${resolved_map_name} for requested ${MAP_NAME}."
    MAP_NAME="${resolved_map_name}"
  fi
  if [[ "${AUTO_WORLD_NAME_FROM_MAP}" == "true" ]] && ! is_placeholder_map_name "${MAP_NAME}"; then
    WORLD_NAME="${MAP_NAME}"
    LEVEL_NAME="${WORLD_NAME}"
  fi
  paper_version=""
  if [[ -f "/server/paper.version" ]]; then
    paper_version="$(cat /server/paper.version 2>/dev/null || true)"
  fi
  legacy_paper="false"
  if [[ "${paper_version}" == 1.8* ]]; then
    legacy_paper="true"
  fi

  if [[ -d "${source_map}" ]]; then
    world_template_dir="$(resolve_world_template_dir "${source_map}")"
    if [[ -z "${world_template_dir}" ]]; then
      echo "[bootstrap] Map directory ${source_map} does not look like a world root (missing level.dat/region)." >&2
    fi
    modern_world_format="false"
    if [[ -n "${world_template_dir}" && ( -d "${world_template_dir}/entities" || -d "${world_template_dir}/poi" ) ]]; then
      modern_world_format="true"
    fi
    if [[ "${legacy_paper}" == "true" && "${modern_world_format}" == "true" ]]; then
      echo "[bootstrap] Skipping map ${GAME_TYPE}/${MAP_NAME}: appears to be modern world format, incompatible with Paper ${paper_version:-1.8.x}."
      echo "[bootstrap] Starting with a freshly generated ${WORLD_NAME} world instead."
    elif [[ -n "${world_template_dir}" ]]; then
      map_identifier="${GAME_TYPE}/${MAP_NAME}"
      target_world_name="${WORLD_NAME}"
      if [[ "${MAP_APPLY_MODE,,}" == "link" || "${MAP_APPLY_MODE,,}" == "symlink" ]]; then
        target_world_name="${MAP_NAME}"
      fi
      target_world_dir="${DATA_DIR}/${target_world_name}"
      if apply_world_template_if_needed "${world_template_dir}" "${target_world_dir}" "${FORCE_MAP_COPY}" "${map_identifier}" "${MAP_APPLY_MODE}"; then
        LEVEL_NAME="${target_world_name}"
        echo "[bootstrap] Using level-name=${LEVEL_NAME} from map directory ${map_identifier} (mode=${MAP_APPLY_MODE})."
      else
        echo "[bootstrap] Failed to apply map template from ${world_template_dir}! Starting with generated ${WORLD_NAME} world." >&2
      fi
    else
      echo "[bootstrap] Starting with a freshly generated ${WORLD_NAME} world instead."
    fi
  else
    echo "[bootstrap] Map directory not found: ${source_map}" >&2
  fi
fi

if [[ -z "${SERVER_TYPE:-}" ]]; then
  case "${SERVER_KIND,,}" in
    hub)
      SERVER_TYPE="MURDER_MYSTERY_HUB"
      ;;
    build)
      SERVER_TYPE="BUILD"
      ;;
    *)
      SERVER_TYPE="MURDER_MYSTERY"
      ;;
  esac
fi

if [[ -z "${SERVER_GROUP:-}" ]]; then
  SERVER_GROUP="${GAME_TYPE}"
fi

if [[ -z "${SERVER_MAX_PLAYERS}" && "${SERVER_KIND}" == "hub" ]]; then
  SERVER_MAX_PLAYERS="100"
fi
if [[ -z "${SERVER_MAX_PLAYERS}" && "${SERVER_KIND}" == "build" ]]; then
  SERVER_MAX_PLAYERS="10"
fi

if [[ -z "${SERVER_ADDRESS:-}" ]]; then
  SERVER_ADDRESS="${HOST_TOKEN}"
fi

if [[ -z "${SERVER_ID:-}" ]]; then
  SERVER_ID="${SERVER_TYPE,,}-${HOST_TOKEN}"
fi

ensure_citizens_plugin || true

if [[ "${SERVER_TYPE^^}" == "BUILD" || "${SERVER_KIND,,}" == "build" ]]; then
  download_build_plugin "Multiverse-Core.jar" \
    "https://mediafilez.forgecdn.net/files/898/527/Multiverse-Core-2.5.jar"
  download_build_plugin "WorldEdit.jar" \
    "https://mediafilez.forgecdn.net/files/2597/538/worldedit-bukkit-6.1.9.jar"
  download_build_plugin "VoxelSniper.jar" \
    "https://mediafilez.forgecdn.net/files/796/868/VoxelSniper-5.170.0-SNAPSHOT.jar"
  download_build_plugin "VoidGenerator.jar" \
    "https://mediafilez.forgecdn.net/files/564/209/VoidGenerator.jar"
fi

if [[ -n "${WORLD_ARCHIVE_URL:-}" ]]; then
  extract_world_archive "${WORLD_ARCHIVE_URL}" "${DATA_DIR}/${WORLD_NAME}" "${WORLD_ARCHIVE_FORCE_EXTRACT:-false}"
fi

allocate_dynamic_port() {
  local key="$1"
  local lock_dir="${PORT_ALLOC_ROOT}/.lock"
  local map_file="${PORT_ALLOC_ROOT}/ports.map"
  local min_port max_port existing used candidate lock_acquired

  min_port="$(printf '%s' "${PORT_ALLOC_MIN}" | tr -cd '0-9')"
  max_port="$(printf '%s' "${PORT_ALLOC_MAX}" | tr -cd '0-9')"
  if [[ -z "${min_port}" ]]; then
    min_port=25590
  fi
  if [[ -z "${max_port}" ]]; then
    max_port=29999
  fi
  if (( max_port < min_port )); then
    max_port="${min_port}"
  fi

  mkdir -p "${PORT_ALLOC_ROOT}"
  touch "${map_file}"

  while ! mkdir "${lock_dir}" 2>/dev/null; do
    sleep 0.05
  done
  lock_acquired=1

  existing="$(awk -F',' -v id="${key}" '$1==id { p=$2 } END { if (p != "") print p }' "${map_file}")"
  if [[ -n "${existing}" ]]; then
    if [[ "${lock_acquired}" == "1" ]]; then
      rmdir "${lock_dir}" 2>/dev/null || true
      lock_acquired=0
    fi
    printf '%s' "${existing}"
    return 0
  fi

  used="$(awk -F',' '{ if ($2 ~ /^[0-9]+$/) print $2 }' "${map_file}" | sort -n | uniq)"
  candidate="${min_port}"
  while (( candidate <= max_port )); do
    if ! printf '%s\n' "${used}" | grep -qx "${candidate}"; then
      printf '%s,%s\n' "${key}" "${candidate}" >> "${map_file}"
      if [[ "${lock_acquired}" == "1" ]]; then
        rmdir "${lock_dir}" 2>/dev/null || true
        lock_acquired=0
      fi
      printf '%s' "${candidate}"
      return 0
    fi
    candidate=$((candidate + 1))
  done

  if [[ "${lock_acquired}" == "1" ]]; then
    rmdir "${lock_dir}" 2>/dev/null || true
    lock_acquired=0
  fi
  return 1
}

DYNAMIC_PORT_ALLOCATED=0

if [[ -z "${SERVER_PORT:-}" || "${SERVER_PORT,,}" == "auto" ]]; then
  allocated_port="$(allocate_dynamic_port "${SERVER_ID}")" || {
    echo "[bootstrap] Failed to allocate dynamic server port from ${PORT_ALLOC_MIN}-${PORT_ALLOC_MAX}!" >&2
    exit 1
  }
  SERVER_PORT="${allocated_port}"
  DYNAMIC_PORT_ALLOCATED=1
fi

release_dynamic_port() {
  local key="$1"
  local lock_dir="${PORT_ALLOC_ROOT}/.lock"
  local map_file="${PORT_ALLOC_ROOT}/ports.map"
  local tmp_file

  if [[ -z "${key}" ]]; then
    return 0
  fi
  mkdir -p "${PORT_ALLOC_ROOT}"
  touch "${map_file}"

  while ! mkdir "${lock_dir}" 2>/dev/null; do
    sleep 0.05
  done

  tmp_file="$(mktemp "${PORT_ALLOC_ROOT}/ports.XXXXXX")"
  awk -F',' -v id="${key}" '$1 != id { print $0 }' "${map_file}" > "${tmp_file}"
  mv "${tmp_file}" "${map_file}"
  rmdir "${lock_dir}" 2>/dev/null || true
}

cleanup_and_release_port() {
  if [[ "${DYNAMIC_PORT_ALLOCATED}" == "1" ]]; then
    release_dynamic_port "${SERVER_ID}"
    DYNAMIC_PORT_ALLOCATED=0
  fi
}

export SERVER_ID
export SERVER_TYPE
export SERVER_GROUP
export SERVER_ADDRESS
export SERVER_PORT="${SERVER_PORT:-25565}"
export SERVER_HEARTBEAT_SECONDS="${SERVER_HEARTBEAT_SECONDS:-1}"
export SERVER_MAX_PLAYERS="${SERVER_MAX_PLAYERS:-}"

server_properties_file="${DATA_DIR}/server.properties"
if [[ -f "${server_properties_file}" ]]; then
  if grep -q '^server-port=' "${server_properties_file}"; then
    sed -i "s/^server-port=.*/server-port=${SERVER_PORT}/" "${server_properties_file}"
  else
    echo "server-port=${SERVER_PORT}" >> "${server_properties_file}"
  fi
  if grep -q '^online-mode=' "${server_properties_file}"; then
    sed -i "s/^online-mode=.*/online-mode=false/" "${server_properties_file}"
  else
    echo "online-mode=false" >> "${server_properties_file}"
  fi
  if grep -q '^allow-nether=' "${server_properties_file}"; then
    sed -i "s/^allow-nether=.*/allow-nether=false/" "${server_properties_file}"
  else
    echo "allow-nether=false" >> "${server_properties_file}"
  fi
  if grep -q '^level-name=' "${server_properties_file}"; then
    sed -i "s|^level-name=.*|level-name=${LEVEL_NAME}|" "${server_properties_file}"
  else
    echo "level-name=${LEVEL_NAME}" >> "${server_properties_file}"
  fi
  if grep -q '^level-type=' "${server_properties_file}"; then
    sed -i "s|^level-type=.*|level-type=${LEVEL_TYPE}|" "${server_properties_file}"
  else
    echo "level-type=${LEVEL_TYPE}" >> "${server_properties_file}"
  fi
  if [[ -n "${GENERATOR_SETTINGS}" ]]; then
    if grep -q '^generator-settings=' "${server_properties_file}"; then
      sed -i "s|^generator-settings=.*|generator-settings=${GENERATOR_SETTINGS}|" "${server_properties_file}"
    else
      echo "generator-settings=${GENERATOR_SETTINGS}" >> "${server_properties_file}"
    fi
  fi
else
  cat > "${server_properties_file}" <<EOF
server-port=${SERVER_PORT}
online-mode=false
allow-nether=false
level-name=${LEVEL_NAME}
level-type=${LEVEL_TYPE}
EOF
  if [[ -n "${GENERATOR_SETTINGS}" ]]; then
    echo "generator-settings=${GENERATOR_SETTINGS}" >> "${server_properties_file}"
  fi
fi

if [[ -n "${SERVER_MAX_PLAYERS}" ]]; then
  max_players_raw="$(printf '%s' "${SERVER_MAX_PLAYERS}" | tr -cd '0-9')"
  if [[ -n "${max_players_raw}" ]] && (( max_players_raw > 0 )); then
    if grep -q '^max-players=' "${server_properties_file}"; then
      sed -i "s/^max-players=.*/max-players=${max_players_raw}/" "${server_properties_file}"
    else
      echo "max-players=${max_players_raw}" >> "${server_properties_file}"
    fi
  fi
fi

echo "eula=true" > "${DATA_DIR}/eula.txt"
cd "${DATA_DIR}"

java ${JAVA_OPTS:-"-Xms512M -Xmx1024M"} -jar /server/paper.jar --noconsole --nojline --port "${SERVER_PORT}" &
paper_pid=$!

forward_shutdown() {
  if kill -0 "${paper_pid}" 2>/dev/null; then
    kill -TERM "${paper_pid}" 2>/dev/null || true
  fi
}

trap 'forward_shutdown' TERM INT

wait "${paper_pid}"
paper_status=$?
cleanup_and_release_port
exit "${paper_status}"

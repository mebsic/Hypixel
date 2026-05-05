#!/usr/bin/env sh
set -eu

version="${VELOCITY_VERSION:-latest}"
user_agent="${USER_AGENT:-hycopy-docker/2.0 (https://example.net)}"
api_root="https://fill.papermc.io/v3/projects/velocity"

http_get() {
  curl --globoff -fsSL -H "User-Agent: ${user_agent}" "$1"
}

list_versions() {
  http_get "${api_root}" \
    | jq -r '
      if (.versions | type) == "object" then
        [.versions[]?[]?]
      elif (.versions | type) == "array" then
        .versions
      else
        []
      end
      | .[]?
    '
}

resolve_latest_build_for_version() {
  target_version="$1"
  builds_doc="$(http_get "${api_root}/versions/${target_version}/builds" || true)"
  if [ -z "${builds_doc}" ]; then
    return 0
  fi

  build="$(printf '%s' "${builds_doc}" \
    | jq -r '[.[]? | (.id // .build // empty) | tonumber?] | map(select(. != null)) | max // empty')"
  if [ -z "${build}" ]; then
    return 0
  fi

  url="$(printf '%s' "${builds_doc}" | jq -r --argjson target "${build}" '
    map(select(((.id // .build // -1) | tonumber?) == $target))
    | .[0].downloads as $downloads
    | if ($downloads | type) != "object" then
        empty
      else
        $downloads["server:default"].url
        // ([$downloads[]? | .url?] | map(select(. != null and . != "")) | .[0] // empty)
      end
  ')"

  if [ -z "${url}" ]; then
    return 0
  fi

  printf '%s|%s\n' "${build}" "${url}"
}

url=""
build=""
if [ "${version}" = "latest" ]; then
  versions="$(list_versions || true)"
  for candidate in $(printf '%s\n' "${versions}" | awk 'NF' | sort -V -r); do
    [ -z "${candidate}" ] && continue
    resolved="$(resolve_latest_build_for_version "${candidate}" || true)"
    if [ -n "${resolved}" ]; then
      version="${candidate}"
      build="$(printf '%s' "${resolved}" | cut -d'|' -f1)"
      url="$(printf '%s' "${resolved}" | cut -d'|' -f2-)"
      break
    fi
  done
else
  resolved="$(resolve_latest_build_for_version "${version}" || true)"
  build="$(printf '%s' "${resolved}" | cut -d'|' -f1)"
  url="$(printf '%s' "${resolved}" | cut -d'|' -f2-)"
fi

if [ -z "${url}" ]; then
  echo "Failed to resolve Velocity download URL from ${api_root} for version ${version}!" >&2
  exit 1
fi

curl --globoff -fsSL -H "User-Agent: ${user_agent}" -o /server/velocity.jar "${url}"
printf '%s\n' "${version}" > /server/velocity.version
if [ -n "${build}" ]; then
  printf '%s\n' "${build}" > /server/velocity.build
fi

#!/usr/bin/env sh
set -eu

version="${VELOCITY_VERSION:-latest}"
user_agent="${USER_AGENT:-hypixel-docker/2.0 (https://example.com)}"
api_root="https://api.papermc.io/v2/projects/velocity"

list_versions() {
  curl --globoff -fsSL -H "User-Agent: ${user_agent}" "${api_root}" \
    | jq -r '.versions[]?'
}

resolve_latest_build() {
  target_version="$1"

  version_doc="$(curl --globoff -fsSL -H "User-Agent: ${user_agent}" "${api_root}/versions/${target_version}" || true)"
  if [ -z "${version_doc}" ]; then
    return 0
  fi

  resolved_version="$(printf '%s' "${version_doc}" | jq -r '.version // empty')"
  if [ -z "${resolved_version}" ]; then
    resolved_version="${target_version}"
  fi

  build="$(printf '%s' "${version_doc}" \
    | jq -r '[.builds[]? | if type=="number" then . else (.build // empty) end | tonumber?] | map(select(. != null)) | sort | last // empty')"

  if [ -z "${build}" ]; then
    builds_doc="$(curl --globoff -fsSL -H "User-Agent: ${user_agent}" "${api_root}/versions/${target_version}/builds" || true)"
    build="$(printf '%s' "${builds_doc}" \
      | jq -r '
        if type == "array" then
          ([.[] | .build? | tonumber?] | map(select(. != null)) | sort | last // empty)
        elif type == "object" then
          ([.builds[]? | if type=="number" then . else (.build // empty) end | tonumber?] | map(select(. != null)) | sort | last // empty)
        else
          empty
        end
      ' )"
  fi

  if [ -z "${build}" ]; then
    return 0
  fi

  file_name="$(printf '%s' "${version_doc}" | jq -r '.downloads.application.name // empty')"
  if [ -z "${file_name}" ]; then
    file_name="velocity-${resolved_version}-${build}.jar"
  fi

  printf '%s\n' "${api_root}/versions/${resolved_version}/builds/${build}/downloads/${file_name}"
}

url=""
if [ "${version}" = "latest" ]; then
  versions="$(list_versions || true)"
  old_ifs="${IFS}"
  IFS='
'
  for candidate in $(printf '%s\n' "${versions}" | sort -V -r); do
    [ -z "${candidate}" ] && continue
    url="$(resolve_latest_build "${candidate}" || true)"
    if [ -n "${url}" ]; then
      version="${candidate}"
      break
    fi
  done
  IFS="${old_ifs}"
else
  url="$(resolve_latest_build "${version}" || true)"
fi

if [ -z "${url}" ]; then
  echo "Failed to resolve Velocity download URL for version ${version}." >&2
  exit 1
fi

curl --globoff -fsSL -H "User-Agent: ${user_agent}" -o /server/velocity.jar "${url}"
printf '%s\n' "${version}" > /server/velocity.version

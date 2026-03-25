#!/usr/bin/env sh
set -eu

version="${PAPER_VERSION:-1.8.8}"
user_agent="${USER_AGENT:-hypixel-docker/2.0 (https://example.net)}"
api_v3_root="https://fill.papermc.io/v3/projects/paper"
api_v2_root="https://api.papermc.io/v2/projects/paper"

resolve_url_v3() {
  target_version="$1"
  curl -fsSL -H "User-Agent: ${user_agent}" "${api_v3_root}/versions/${target_version}/builds" \
    | jq -r 'first(.[] | select(.channel=="STABLE") | .downloads."server:default".url) // first(.[] | .downloads."server:default".url) // empty'
}

resolve_url_v2() {
  target_version="$1"
  curl -fsSL -H "User-Agent: ${user_agent}" "${api_v2_root}/versions/${target_version}/builds" \
    | jq -r --arg root "${api_v2_root}" '
      . as $doc
      | ($doc.builds // [] | sort_by(.build) | last) as $build
      | ($build.downloads.application.name // empty) as $name
      | if $name == "" then
          empty
        else
          $root + "/versions/" + $doc.version + "/builds/" + ($build.build | tostring) + "/downloads/" + $name
        end
    '
}

resolve_url() {
  target_version="$1"
  url="$(resolve_url_v3 "${target_version}" || true)"
  if [ -n "${url}" ]; then
    printf '%s\n' "${url}"
    return
  fi
  resolve_url_v2 "${target_version}" || true
}

resolve_latest_legacy_version() {
  fallback="$(curl -fsSL -H "User-Agent: ${user_agent}" "${api_v3_root}" | jq -r '.versions[]' | grep '^1\\.8\\.' | sort -V -r | head -n 1 || true)"
  if [ -n "${fallback}" ]; then
    printf '%s\n' "${fallback}"
    return
  fi
  curl -fsSL -H "User-Agent: ${user_agent}" "${api_v2_root}" \
    | jq -r '.versions[]' \
    | grep '^1\\.8\\.' \
    | sort -V -r \
    | head -n 1 || true
}

url="$(resolve_url "${version}" || true)"
if [ -z "${url}" ]; then
  echo "Failed to find Paper ${version}! Trying latest available 1.8.x build." >&2
  fallback="$(resolve_latest_legacy_version || true)"
  if [ -z "${fallback}" ]; then
    echo "No fallback Paper 1.8.x version is available from the API." >&2
    exit 1
  fi
  version="${fallback}"
  url="$(resolve_url "${version}" || true)"
fi

if [ -z "${url}" ]; then
  echo "Failed to resolve a Paper download URL!" >&2
  exit 1
fi

curl -fsSL -H "User-Agent: ${user_agent}" -o /server/paper.jar "${url}"
printf '%s\n' "${version}" > /server/paper.version

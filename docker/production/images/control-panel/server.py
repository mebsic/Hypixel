#!/usr/bin/env python3
import datetime
import errno
import http.client
import http.server
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import threading
import time
import urllib.parse
import zipfile

from pymongo import MongoClient, ReturnDocument


def env_bool(name, default):
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def env_int(name, default, minimum=None):
    value = os.getenv(name)
    if value is None or not value.strip():
        parsed = default
    else:
        try:
            parsed = int(value.strip())
        except ValueError:
            parsed = default
    if minimum is not None and parsed < minimum:
        return minimum
    return parsed


def clean_csv(value, fallback):
    source = value if value is not None else fallback
    items = [item.strip() for item in source.split(",")]
    return [item for item in items if item]


DOCKER_SOCKET = os.getenv("DOCKER_SOCKET", "/var/run/docker.sock")
LISTEN_HOST = os.getenv("ROLLOUT_LISTEN_HOST", "0.0.0.0")
LISTEN_PORT = env_int("ROLLOUT_LISTEN_PORT", 8080, minimum=1)
ROLLOUT_TOKEN = os.getenv("ROLLOUT_TOKEN", "").strip()
COMPOSE_PROJECT = os.getenv("COMPOSE_PROJECT", "").strip()

RESTART_TIMEOUT_SECONDS = env_int("ROLLOUT_RESTART_TIMEOUT_SECONDS", 10, minimum=1)
RESTART_HEALTH_WAIT_SECONDS = env_int("ROLLOUT_RESTART_HEALTH_WAIT_SECONDS", 180, minimum=5)
INCLUDE_SERVICES = {
    (item or "").strip().lower()
    for item in clean_csv(os.getenv("ROLLOUT_INCLUDE_SERVICES"), "")
    if (item or "").strip()
}
INCLUDE_PREFIXES = tuple(
    (item or "").strip().lower()
    for item in clean_csv(os.getenv("ROLLOUT_INCLUDE_PREFIXES"), "")
    if (item or "").strip()
)
EXCLUDED_SERVICES = {"mongo", "redis", "control-panel"}
RESTART_SERVICE_ORDER = tuple(
    (item or "").strip().lower()
    for item in clean_csv(
        os.getenv("ROLLOUT_RESTART_SERVICE_ORDER"),
        "murder-mystery-hub,murder-mystery-game,build,velocity",
    )
    if (item or "").strip()
)
VALID_RESTART_MODES = {"restart", "recreate", "rebuild"}
ROLLOUT_RESTART_MODE = (os.getenv("ROLLOUT_RESTART_MODE", "restart").strip().lower() or "restart")
if ROLLOUT_RESTART_MODE not in VALID_RESTART_MODES:
    print(f"invalid ROLLOUT_RESTART_MODE={ROLLOUT_RESTART_MODE!r}; falling back to 'restart'")
    ROLLOUT_RESTART_MODE = "restart"
ROLLOUT_MIN_HUB_REPLICAS = env_int("ROLLOUT_MIN_HUB_REPLICAS", 2, minimum=2)
ROLLOUT_MIN_GAME_REPLICAS = env_int("ROLLOUT_MIN_GAME_REPLICAS", 2, minimum=2)

ROLLOUT_COMPOSE_WORKDIR = os.getenv("ROLLOUT_COMPOSE_WORKDIR", "/workspace").strip()
ROLLOUT_COMPOSE_FILE = os.getenv("ROLLOUT_COMPOSE_FILE", "/workspace/docker-compose.yml").strip()
ROLLOUT_COMPOSE_ENV_FILE = os.getenv("ROLLOUT_COMPOSE_ENV_FILE", "/workspace/.env").strip()
ROLLOUT_PLUGIN_DIR = os.getenv("ROLLOUT_PLUGIN_DIR", "/workspace/docker/production/plugins").strip()
ROLLOUT_PLUGIN_SOURCE_DIR = os.getenv("ROLLOUT_PLUGIN_SOURCE_DIR", "/workspace/docker/development/plugins").strip()
REQUIRED_PLUGIN_FILENAMES = (
    "Hypixel.jar",
    "MurderMystery.jar",
    "HypixelBuild.jar",
    "HypixelProxy.jar",
)

AUTOSCALE_ENABLED = env_bool("AUTOSCALE_ENABLED", True)
AUTOSCALE_INTERVAL_SECONDS = env_int("AUTOSCALE_INTERVAL_SECONDS", 60, minimum=5)
AUTOSCALE_STARTUP_GRACE_SECONDS = env_int("AUTOSCALE_STARTUP_GRACE_SECONDS", 60, minimum=0)
AUTOSCALE_STARTUP_WAIT_FOR_READY_SERVICES = env_bool("AUTOSCALE_STARTUP_WAIT_FOR_READY_SERVICES", True)
AUTOSCALE_STARTUP_WAIT_TIMEOUT_SECONDS = env_int("AUTOSCALE_STARTUP_WAIT_TIMEOUT_SECONDS", 300, minimum=0)
AUTOSCALE_DEFAULT_GAME_TYPE = os.getenv("AUTOSCALE_DEFAULT_GAME_TYPE", "murdermystery").strip() or "murdermystery"
AUTOSCALE_REGISTRY_COLLECTION = os.getenv("AUTOSCALE_REGISTRY_COLLECTION", "server_registry").strip() or "server_registry"
AUTOSCALE_COLLECTION = os.getenv("AUTOSCALE_COLLECTION", "autoscale").strip() or "autoscale"
AUTOSCALE_STALE_HEARTBEAT_MILLIS = env_int("AUTOSCALE_STALE_HEARTBEAT_MILLIS", 120000, minimum=1000)
AUTOSCALE_PLAYERS_TTL_SECONDS = env_int("AUTOSCALE_PLAYERS_TTL_SECONDS", 180, minimum=10)
AUTOSCALE_DRAIN_TIMEOUT_SECONDS = env_int("AUTOSCALE_DRAIN_TIMEOUT_SECONDS", 240, minimum=30)
AUTOSCALE_SCALE_UP_COOLDOWN_SECONDS = env_int("AUTOSCALE_SCALE_UP_COOLDOWN_SECONDS", 90, minimum=0)
AUTOSCALE_SCALE_DOWN_COOLDOWN_SECONDS = env_int("AUTOSCALE_SCALE_DOWN_COOLDOWN_SECONDS", 300, minimum=0)
AUTOSCALE_HYSTERESIS_PLAYERS = env_int("AUTOSCALE_HYSTERESIS_PLAYERS", 20, minimum=0)
AUTOSCALE_POLICY_DEFAULT_PLAYERS_PER_STEP = env_int("AUTOSCALE_POLICY_DEFAULT_PLAYERS_PER_STEP", 75, minimum=1)
AUTOSCALE_POLICY_DEFAULT_HUB_PER_STEP = env_int("AUTOSCALE_POLICY_DEFAULT_HUB_PER_STEP", 1, minimum=0)
AUTOSCALE_POLICY_DEFAULT_GAME_PER_STEP = env_int("AUTOSCALE_POLICY_DEFAULT_GAME_PER_STEP", 1, minimum=0)
AUTOSCALE_POLICY_DEFAULT_BASE_HUB = env_int("AUTOSCALE_POLICY_DEFAULT_BASE_HUB", 2, minimum=2)
AUTOSCALE_POLICY_DEFAULT_BASE_GAME = env_int("AUTOSCALE_POLICY_DEFAULT_BASE_GAME", 2, minimum=2)
AUTOSCALE_POLICY_DEFAULT_MIN_HUB = env_int("AUTOSCALE_POLICY_DEFAULT_MIN_HUB", 2, minimum=2)
AUTOSCALE_POLICY_DEFAULT_MIN_GAME = env_int("AUTOSCALE_POLICY_DEFAULT_MIN_GAME", 2, minimum=2)
AUTOSCALE_POLICY_DEFAULT_MAX_HUB = env_int("AUTOSCALE_POLICY_DEFAULT_MAX_HUB", 12, minimum=1)
AUTOSCALE_POLICY_DEFAULT_MAX_GAME = env_int("AUTOSCALE_POLICY_DEFAULT_MAX_GAME", 24, minimum=1)

MONGO_URI = os.getenv("MONGO_URI", "").strip()
MONGO_DATABASE = os.getenv("MONGO_DATABASE", "").strip()

autoscale_lock = threading.Lock()
autoscale_stop_event = threading.Event()
mongo_client_lock = threading.Lock()
mongo_client = None


def now_ms():
    return int(time.time() * 1000)


def now_iso():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def to_int(value, default=0):
    if value is None:
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


class UnixHTTPConnection(http.client.HTTPConnection):
    def __init__(self, unix_socket_path, timeout=10):
        super().__init__("localhost", timeout=timeout)
        self.unix_socket_path = unix_socket_path

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self.unix_socket_path)


def docker_request(method, path, body=None):
    connection = UnixHTTPConnection(DOCKER_SOCKET, timeout=20)
    payload = None
    headers = {}
    if body is not None:
        payload = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    connection.request(method, path, body=payload, headers=headers)
    response = connection.getresponse()
    data = response.read()
    status = response.status
    connection.close()
    return status, data


def docker_json(method, path):
    status, data = docker_request(method, path)
    if status < 200 or status >= 300:
        detail = data.decode("utf-8", errors="replace")
        raise RuntimeError(f"Docker API {method} {path} failed: {status} {detail}")
    if not data:
        return None
    return json.loads(data.decode("utf-8"))


def detect_compose_project():
    if COMPOSE_PROJECT:
        return COMPOSE_PROJECT

    hostname = (os.getenv("HOSTNAME") or "").strip()
    if not hostname:
        return ""

    encoded = urllib.parse.quote(hostname, safe="")
    inspect = docker_json("GET", f"/containers/{encoded}/json")
    labels = inspect.get("Config", {}).get("Labels", {})
    return labels.get("com.docker.compose.project", "")


def normalize_service_slug(value):
    normalized = (value or "").strip().lower()
    normalized = normalized.replace("_", "-").replace(" ", "-")
    normalized = re.sub(r"[^a-z0-9-]+", "-", normalized)
    normalized = re.sub(r"-{2,}", "-", normalized).strip("-")
    return normalized


def service_game_type_slug(game_type):
    normalized = normalize_game_type(game_type)
    aliases = {
        "murdermystery": "murder-mystery",
    }
    alias = aliases.get(normalized, normalized)
    slug = normalize_service_slug(alias)
    return slug or "game-type"


def default_game_type_service_name(game_type, kind):
    resolved_kind = "hub" if (kind or "").strip().lower() == "hub" else "game"
    return f"{service_game_type_slug(game_type)}-{resolved_kind}"


def classify_service_kind(service_name):
    normalized = normalize_service_slug(service_name)
    if not normalized:
        return None
    if normalized == "hub" or normalized.endswith("-hub"):
        return "hub"
    if normalized == "game" or normalized.endswith("-game"):
        return "game"
    return None


def is_restart_target_service(service):
    normalized = (service or "").strip().lower()
    if not normalized or normalized in EXCLUDED_SERVICES:
        return False
    if not INCLUDE_SERVICES and not INCLUDE_PREFIXES:
        return True
    if "*" in INCLUDE_SERVICES:
        return True
    if normalized in INCLUDE_SERVICES:
        return True
    for prefix in INCLUDE_PREFIXES:
        normalized_prefix = (prefix or "").strip().lower()
        if not normalized_prefix:
            continue
        if normalized.startswith(normalized_prefix) or normalized.endswith(normalized_prefix):
            return True
    return False


def list_project_containers(compose_project, include_all=True):
    filters = {"label": [f"com.docker.compose.project={compose_project}"]}
    encoded_filters = urllib.parse.quote(json.dumps(filters), safe="")
    all_value = "1" if include_all else "0"
    raw = docker_json("GET", f"/containers/json?all={all_value}&filters={encoded_filters}")
    containers = []
    for item in raw:
        names = item.get("Names") or []
        name = names[0].lstrip("/") if names else item.get("Id", "")[:12]
        labels = item.get("Labels") or {}
        containers.append(
            {
                "id": item.get("Id", ""),
                "name": name,
                "service": labels.get("com.docker.compose.service", ""),
                "state": (item.get("State") or "").lower(),
                "status": item.get("Status", ""),
            }
        )
    containers.sort(key=lambda value: (value["service"], value["name"]))
    return containers


def restart_container(container_id):
    encoded = urllib.parse.quote(container_id, safe="")
    status, data = docker_request("POST", f"/containers/{encoded}/restart?t={RESTART_TIMEOUT_SECONDS}")
    if status in (204, 304):
        return True, ""
    detail = data.decode("utf-8", errors="replace")
    return False, f"restart failed with status {status}: {detail}"


def inspect_container_state(container_id):
    encoded = urllib.parse.quote(container_id, safe="")
    inspect = docker_json("GET", f"/containers/{encoded}/json")
    state = inspect.get("State") or {}
    lifecycle_status = (state.get("Status") or "").lower()
    health = state.get("Health") or {}
    health_status = (health.get("Status") or "").lower()
    return lifecycle_status, health_status


def is_container_ready_state(lifecycle_status, health_status):
    if lifecycle_status != "running":
        return False
    if not health_status:
        return True
    return health_status == "healthy"


def wait_for_container_ready(container_id, timeout_seconds):
    deadline = time.time() + max(1, timeout_seconds)
    last_detail = "state=unknown"

    while time.time() < deadline:
        try:
            lifecycle_status, health_status = inspect_container_state(container_id)
        except Exception as exc:
            last_detail = f"inspect failed: {exc}"
            time.sleep(1)
            continue

        if lifecycle_status in {"dead", "exited", "removing"}:
            return False, f"state={lifecycle_status}"

        if is_container_ready_state(lifecycle_status, health_status):
            if health_status:
                return True, f"state={lifecycle_status},health={health_status}"
            return True, f"state={lifecycle_status}"

        if health_status:
            last_detail = f"state={lifecycle_status},health={health_status}"
        else:
            last_detail = f"state={lifecycle_status}"
        time.sleep(1)

    return False, f"timed out after {timeout_seconds}s ({last_detail})"


def ordered_restart_targets(targets):
    order = {(service or "").strip().lower(): index for index, service in enumerate(RESTART_SERVICE_ORDER)}
    unknown_rank = len(order)
    return sorted(
        targets,
        key=lambda item: (
            order.get((item.get("service", "") or "").strip().lower(), order.get(classify_service_kind(item.get("service", "")), unknown_rank)),
            item.get("service", ""),
            item.get("name", ""),
        ),
    )


def ordered_target_services(targets):
    ordered = []
    seen = set()
    for item in targets:
        service = (item.get("service") or "").strip()
        if not service or service in seen:
            continue
        seen.add(service)
        ordered.append(service)
    return ordered


def resolve_restart_targets(containers, requested_services=None):
    base_targets = [item for item in containers if is_restart_target_service(item.get("service", ""))]
    ordered = ordered_restart_targets(base_targets)
    if requested_services is None:
        return ordered
    requested = set((service or "").strip().lower() for service in requested_services if service)
    if not requested:
        return ordered
    requested_kinds = {item for item in requested if item in {"hub", "game"}}
    filtered = []
    for item in ordered:
        service = (item.get("service", "") or "").strip().lower()
        if service in requested:
            filtered.append(item)
            continue
        if classify_service_kind(service) in requested_kinds:
            filtered.append(item)
    return filtered


def count_ready_service_containers(compose_project, service_name):
    ready = 0
    containers = list_project_containers(compose_project, include_all=True)
    for container in containers:
        if container.get("service") != service_name:
            continue
        try:
            lifecycle_status, health_status = inspect_container_state(container.get("id", ""))
        except Exception:
            continue
        if is_container_ready_state(lifecycle_status, health_status):
            ready += 1
    return ready


def count_ready_kind_containers(compose_project, kind, allowed_services=None):
    target_kind = classify_service_kind(kind)
    if target_kind is None:
        return 0

    allowed = None
    if allowed_services:
        allowed = {
            (service or "").strip()
            for service in allowed_services
            if isinstance(service, str) and service.strip()
        }
        if not allowed:
            allowed = None

    ready = 0
    containers = list_project_containers(compose_project, include_all=True)
    for container in containers:
        service = (container.get("service") or "").strip()
        if allowed is not None and service not in allowed:
            continue
        if classify_service_kind(service) != target_kind:
            continue
        try:
            lifecycle_status, health_status = inspect_container_state(container.get("id", ""))
        except Exception:
            continue
        if is_container_ready_state(lifecycle_status, health_status):
            ready += 1
    return ready


def desired_service_replicas(containers, service_name):
    minimum = 1
    service_kind = classify_service_kind(service_name)
    if service_kind == "hub":
        minimum = ROLLOUT_MIN_HUB_REPLICAS
    elif service_kind == "game":
        minimum = ROLLOUT_MIN_GAME_REPLICAS

    active = len(
        [item for item in containers if item.get("service") == service_name and is_container_active(item)]
    )
    if active > 0:
        return max(minimum, active)
    total = len([item for item in containers if item.get("service") == service_name])
    if total > 0:
        return max(minimum, total)
    return minimum


def wait_for_service_ready(compose_project, service_name, expected_count, timeout_seconds):
    expected = max(1, to_int(expected_count, 1))
    deadline = time.time() + max(1, timeout_seconds)
    last_detail = "state=unknown"

    while time.time() < deadline:
        try:
            containers = list_project_containers(compose_project, include_all=True)
        except Exception as exc:
            last_detail = f"list containers failed: {exc}"
            time.sleep(1)
            continue

        service_containers = [item for item in containers if item.get("service") == service_name]
        active_count = len([item for item in service_containers if is_container_active(item)])
        ready_count = 0
        for item in service_containers:
            try:
                lifecycle_status, health_status = inspect_container_state(item.get("id", ""))
            except Exception:
                continue
            if is_container_ready_state(lifecycle_status, health_status):
                ready_count += 1

        if active_count >= expected and ready_count >= expected:
            return True, f"ready={ready_count}/{expected},active={active_count}/{expected}"

        last_detail = f"ready={ready_count}/{expected},active={active_count}/{expected},seen={len(service_containers)}"
        time.sleep(1)

    return False, f"timed out after {timeout_seconds}s ({last_detail})"


def normalize_restart_mode(value):
    mode = str(value or "").strip().lower()
    return mode if mode in VALID_RESTART_MODES else ""


def parse_requested_services(value):
    if value is None:
        return True, None

    raw_values = []
    if isinstance(value, str):
        raw_values = clean_csv(value, "")
    elif isinstance(value, (list, tuple, set)):
        for item in value:
            if not isinstance(item, str):
                return False, None
            raw_values.append(item)
    else:
        return False, None

    requested = []
    seen = set()
    for item in raw_values:
        normalized = (item or "").strip().lower()
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        requested.append(normalized)
    return True, requested


def merge_requested_services(primary, secondary):
    if primary is None and secondary is None:
        return None
    merged = []
    seen = set()
    for values in (primary, secondary):
        if values is None:
            continue
        for item in values:
            normalized = (item or "").strip().lower()
            if not normalized or normalized in seen:
                continue
            seen.add(normalized)
            merged.append(normalized)
    return merged


def parse_requested_server_ids(value):
    return parse_requested_services(value)


def merge_requested_server_ids(primary, secondary):
    return merge_requested_services(primary, secondary)


def stop_and_remove_container(container_id):
    encoded = urllib.parse.quote(container_id, safe="")
    stop_status, stop_body = docker_request("POST", f"/containers/{encoded}/stop?t=10")
    if stop_status not in (204, 304, 404):
        detail = stop_body.decode("utf-8", errors="replace")
        raise RuntimeError(f"stop failed ({stop_status}): {detail}")
    remove_status, remove_body = docker_request("DELETE", f"/containers/{encoded}?force=1")
    if remove_status not in (204, 404):
        detail = remove_body.decode("utf-8", errors="replace")
        raise RuntimeError(f"remove failed ({remove_status}): {detail}")


def compose_command_prefix(compose_project):
    if shutil.which("docker"):
        cmd = ["docker", "compose"]
    elif shutil.which("docker-compose"):
        cmd = ["docker-compose"]
    else:
        raise RuntimeError("docker compose command not found in control-panel container")
    if compose_project:
        cmd.extend(["-p", compose_project])
    if ROLLOUT_COMPOSE_FILE:
        cmd.extend(["-f", ROLLOUT_COMPOSE_FILE])
    if ROLLOUT_COMPOSE_ENV_FILE and os.path.isfile(ROLLOUT_COMPOSE_ENV_FILE):
        cmd.extend(["--env-file", ROLLOUT_COMPOSE_ENV_FILE])
    return cmd


def compose_subprocess_env():
    compose_env = os.environ.copy()
    if not compose_env.get("PWD"):
        candidate = (ROLLOUT_COMPOSE_WORKDIR or "").strip()
        if not candidate:
            candidate = os.path.dirname((ROLLOUT_COMPOSE_FILE or "").strip())
        if not candidate:
            candidate = "/workspace"
        compose_env["PWD"] = candidate
    return compose_env


def run_compose_scale_service(compose_project, service_name, replicas):
    cmd = compose_command_prefix(compose_project)
    cmd.extend(
        [
            "up",
            "-d",
            "--no-deps",
            "--no-recreate",
            "--scale",
            f"{service_name}={replicas}",
            service_name,
        ]
    )
    compose_env = compose_subprocess_env()
    result = subprocess.run(
        cmd,
        cwd=ROLLOUT_COMPOSE_WORKDIR if ROLLOUT_COMPOSE_WORKDIR else None,
        env=compose_env,
        capture_output=True,
        text=True,
        check=False,
    )
    combined = ((result.stdout or "") + "\n" + (result.stderr or "")).strip()
    if result.returncode != 0:
        raise RuntimeError(f"compose scale failed for service {service_name}: {combined}")
    return combined


def run_compose_up_service(compose_project, service_name, replicas, build, force_recreate):
    cmd = compose_command_prefix(compose_project)
    cmd.extend(["up", "-d", "--no-deps"])
    if build:
        cmd.append("--build")
    if force_recreate:
        cmd.append("--force-recreate")
    if replicas is not None and replicas > 0:
        cmd.extend(["--scale", f"{service_name}={replicas}"])
    cmd.append(service_name)

    result = subprocess.run(
        cmd,
        cwd=ROLLOUT_COMPOSE_WORKDIR if ROLLOUT_COMPOSE_WORKDIR else None,
        env=compose_subprocess_env(),
        capture_output=True,
        text=True,
        check=False,
    )
    combined = ((result.stdout or "") + "\n" + (result.stderr or "")).strip()
    if result.returncode != 0:
        raise RuntimeError(f"compose up failed: {combined}")
    return combined


def validate_jar(path):
    if not os.path.isfile(path):
        return False, "file missing"
    try:
        size = os.path.getsize(path)
    except OSError as exc:
        return False, f"size check failed: {exc}"
    if size <= 0:
        return False, "file is empty"
    try:
        with zipfile.ZipFile(path, "r") as jar_file:
            bad_entry = jar_file.testzip()
            if bad_entry is not None:
                return False, f"corrupt entry: {bad_entry}"
    except zipfile.BadZipFile as exc:
        return False, f"bad zip/jar: {exc}"
    except Exception as exc:
        return False, f"jar validation failed: {exc}"
    return True, "ok"


def validate_rollout_plugins():
    if not ROLLOUT_PLUGIN_DIR:
        raise RuntimeError("ROLLOUT_PLUGIN_DIR is required")

    os.makedirs(ROLLOUT_PLUGIN_DIR, exist_ok=True)
    result = {
        "pluginDir": ROLLOUT_PLUGIN_DIR,
        "validated": [],
    }
    for file_name in REQUIRED_PLUGIN_FILENAMES:
        source_path = os.path.join(ROLLOUT_PLUGIN_DIR, file_name)
        valid, detail = validate_jar(source_path)
        if not valid:
            raise RuntimeError(f"{file_name} failed validation: {detail}")
        result["validated"].append(file_name)
    return result


def sync_source_rollout_plugins():
    if not ROLLOUT_PLUGIN_DIR:
        raise RuntimeError("ROLLOUT_PLUGIN_DIR is required")

    os.makedirs(ROLLOUT_PLUGIN_DIR, exist_ok=True)
    result = {
        "sourceDir": ROLLOUT_PLUGIN_SOURCE_DIR,
        "pluginDir": ROLLOUT_PLUGIN_DIR,
        "copied": [],
        "missingSource": [],
    }
    if not ROLLOUT_PLUGIN_SOURCE_DIR:
        return result

    for file_name in REQUIRED_PLUGIN_FILENAMES:
        source_path = os.path.join(ROLLOUT_PLUGIN_SOURCE_DIR, file_name)
        if not os.path.isfile(source_path):
            result["missingSource"].append(file_name)
            continue

        destination_path = os.path.join(ROLLOUT_PLUGIN_DIR, file_name)
        try:
            shutil.copy2(source_path, destination_path)
        except OSError as exc:
            raise RuntimeError(f"failed to copy {file_name} from plugin source: {exc}")
        result["copied"].append(file_name)

    return result


def docker_daemon_ready():
    try:
        status, data = docker_request("GET", "/_ping")
        body = data.decode("utf-8", errors="replace").strip().upper()
        if status != 200 or body != "OK":
            return False, f"unexpected docker ping response ({status}, body={body})"
        return True, "ok"
    except Exception as exc:
        return False, str(exc)


def mongo_ready():
    try:
        db = get_mongo_database()
        response = db.command("ping")
        if to_int(response.get("ok"), 0) != 1:
            return False, f"unexpected mongo ping response ({response})"
        return True, "ok"
    except Exception as exc:
        return False, str(exc)


def compose_project_ready():
    try:
        compose_project = detect_compose_project()
        if not compose_project:
            return False, "", "compose project not detected"
        list_project_containers(compose_project, include_all=False)
        return True, compose_project, "ok"
    except Exception as exc:
        return False, "", str(exc)


def build_readiness_payload():
    payload = {"ok": True, "checks": {}}

    docker_ok, docker_detail = docker_daemon_ready()
    payload["checks"]["docker"] = {"ok": docker_ok, "detail": docker_detail}
    payload["ok"] = payload["ok"] and docker_ok

    compose_ok, compose_project, compose_detail = compose_project_ready()
    payload["checks"]["composeProject"] = {
        "ok": compose_ok,
        "project": compose_project,
        "detail": compose_detail,
    }
    payload["ok"] = payload["ok"] and compose_ok

    mongo_ok, mongo_detail = mongo_ready()
    payload["checks"]["mongo"] = {"ok": mongo_ok, "detail": mongo_detail}
    payload["ok"] = payload["ok"] and mongo_ok

    return payload


def get_mongo_database():
    global mongo_client
    if not MONGO_URI or not MONGO_DATABASE:
        raise RuntimeError("MONGO_URI and MONGO_DATABASE are required for autoscale")
    with mongo_client_lock:
        if mongo_client is None:
            mongo_client = MongoClient(
                MONGO_URI,
                serverSelectionTimeoutMS=5000,
                connectTimeoutMS=3000,
                socketTimeoutMS=5000,
            )
    return mongo_client[MONGO_DATABASE]


def classify_kind(type_id):
    normalized = (type_id or "").strip().upper()
    if not normalized:
        return None
    if normalized.endswith("_HUB"):
        return "hub"
    if normalized in {"BUILD", "PROXY", "UNKNOWN"}:
        return None
    return "game"


def normalize_game_type(group):
    value = (group or "").strip().lower()
    return value if value else AUTOSCALE_DEFAULT_GAME_TYPE


def autoscale_doc_id(doc_type, game_type):
    return f"{doc_type}:{normalize_game_type(game_type)}"


def autoscale_collection(db):
    return db[AUTOSCALE_COLLECTION]


def extract_game_type(raw, doc_type):
    explicit = (raw.get("gameType") or "").strip().lower()
    if explicit:
        return normalize_game_type(explicit)
    raw_id = str(raw.get("_id", "")).strip()
    prefix = f"{doc_type}:"
    if raw_id.lower().startswith(prefix):
        tail = raw_id[len(prefix):].strip().lower()
        if tail:
            return normalize_game_type(tail)
    if raw_id:
        return normalize_game_type(raw_id)
    return AUTOSCALE_DEFAULT_GAME_TYPE


def is_container_active(container):
    return container.get("state") in {"running", "restarting"}


def default_policy(game_type):
    normalized_game_type = normalize_game_type(game_type)
    min_hub = max(0, AUTOSCALE_POLICY_DEFAULT_MIN_HUB)
    min_game = max(0, AUTOSCALE_POLICY_DEFAULT_MIN_GAME)
    base_hub = max(min_hub, AUTOSCALE_POLICY_DEFAULT_BASE_HUB)
    base_game = max(min_game, AUTOSCALE_POLICY_DEFAULT_BASE_GAME)
    max_hub = max(base_hub, AUTOSCALE_POLICY_DEFAULT_MAX_HUB)
    max_game = max(base_game, AUTOSCALE_POLICY_DEFAULT_MAX_GAME)
    return {
        "docType": "policy",
        "gameType": normalized_game_type,
        "playersPerStep": AUTOSCALE_POLICY_DEFAULT_PLAYERS_PER_STEP,
        "hubPerStep": AUTOSCALE_POLICY_DEFAULT_HUB_PER_STEP,
        "gamePerStep": AUTOSCALE_POLICY_DEFAULT_GAME_PER_STEP,
        "baseHub": base_hub,
        "baseGame": base_game,
        "minHub": min_hub,
        "minGame": min_game,
        "maxHub": max_hub,
        "maxGame": max_game,
        "hubService": default_game_type_service_name(normalized_game_type, "hub"),
        "gameService": default_game_type_service_name(normalized_game_type, "game"),
        "scaleUpCooldownSeconds": AUTOSCALE_SCALE_UP_COOLDOWN_SECONDS,
        "scaleDownCooldownSeconds": AUTOSCALE_SCALE_DOWN_COOLDOWN_SECONDS,
        "hysteresisPlayers": AUTOSCALE_HYSTERESIS_PLAYERS,
        "drainTimeoutSeconds": AUTOSCALE_DRAIN_TIMEOUT_SECONDS,
        "updatedAt": now_iso(),
    }


def normalize_policy(raw):
    game_type = extract_game_type(raw, "policy")
    default_hub_service = default_game_type_service_name(game_type, "hub")
    default_game_service = default_game_type_service_name(game_type, "game")
    players_per_step = max(1, to_int(raw.get("playersPerStep"), AUTOSCALE_POLICY_DEFAULT_PLAYERS_PER_STEP))
    hub_per_step = max(0, to_int(raw.get("hubPerStep"), AUTOSCALE_POLICY_DEFAULT_HUB_PER_STEP))
    game_per_step = max(0, to_int(raw.get("gamePerStep"), AUTOSCALE_POLICY_DEFAULT_GAME_PER_STEP))
    base_hub = max(2, to_int(raw.get("baseHub"), AUTOSCALE_POLICY_DEFAULT_BASE_HUB))
    base_game = max(2, to_int(raw.get("baseGame"), AUTOSCALE_POLICY_DEFAULT_BASE_GAME))
    min_hub = max(2, to_int(raw.get("minHub"), base_hub))
    min_game = max(2, to_int(raw.get("minGame"), base_game))
    max_hub = max(min_hub, to_int(raw.get("maxHub"), AUTOSCALE_POLICY_DEFAULT_MAX_HUB))
    max_game = max(min_game, to_int(raw.get("maxGame"), AUTOSCALE_POLICY_DEFAULT_MAX_GAME))
    raw_hub_service = (raw.get("hubService") or "").strip()
    raw_game_service = (raw.get("gameService") or "").strip()
    hub_service = raw_hub_service if raw_hub_service and raw_hub_service.lower() != "hub" else default_hub_service
    game_service = raw_game_service if raw_game_service and raw_game_service.lower() != "game" else default_game_service
    up_cd = max(0, to_int(raw.get("scaleUpCooldownSeconds"), AUTOSCALE_SCALE_UP_COOLDOWN_SECONDS))
    down_cd = max(0, to_int(raw.get("scaleDownCooldownSeconds"), AUTOSCALE_SCALE_DOWN_COOLDOWN_SECONDS))
    hysteresis = max(0, to_int(raw.get("hysteresisPlayers"), AUTOSCALE_HYSTERESIS_PLAYERS))
    drain_timeout = max(30, to_int(raw.get("drainTimeoutSeconds"), AUTOSCALE_DRAIN_TIMEOUT_SECONDS))

    return {
        "gameType": game_type,
        "playersPerStep": players_per_step,
        "hubPerStep": hub_per_step,
        "gamePerStep": game_per_step,
        "baseHub": base_hub,
        "baseGame": base_game,
        "minHub": min_hub,
        "minGame": min_game,
        "maxHub": max_hub,
        "maxGame": max_game,
        "hubService": hub_service,
        "gameService": game_service,
        "scaleUpCooldownMs": up_cd * 1000,
        "scaleDownCooldownMs": down_cd * 1000,
        "hysteresisPlayers": hysteresis,
        "drainTimeoutMs": drain_timeout * 1000,
    }


def matches_previous_default_policy(doc):
    if not doc:
        return False
    return (
        to_int(doc.get("playersPerStep"), 50) == 50
        and to_int(doc.get("hubPerStep"), 2) == 2
        and to_int(doc.get("gamePerStep"), 4) == 4
        and to_int(doc.get("baseHub"), 2) == 2
        and to_int(doc.get("baseGame"), 2) == 2
        and to_int(doc.get("minHub"), 2) == 2
        and to_int(doc.get("minGame"), 2) == 2
        and to_int(doc.get("maxHub"), 60) == 60
        and to_int(doc.get("maxGame"), 120) == 120
        and (doc.get("hubService") or "hub") == "hub"
        and (doc.get("gameService") or "game") == "game"
    )


def conservative_policy_update(game_type):
    defaults = default_policy(game_type)
    return {
        "playersPerStep": defaults["playersPerStep"],
        "hubPerStep": defaults["hubPerStep"],
        "gamePerStep": defaults["gamePerStep"],
        "baseHub": defaults["baseHub"],
        "baseGame": defaults["baseGame"],
        "minHub": defaults["minHub"],
        "minGame": defaults["minGame"],
        "maxHub": defaults["maxHub"],
        "maxGame": defaults["maxGame"],
        "hubService": defaults["hubService"],
        "gameService": defaults["gameService"],
        "scaleUpCooldownSeconds": AUTOSCALE_SCALE_UP_COOLDOWN_SECONDS,
        "hysteresisPlayers": AUTOSCALE_HYSTERESIS_PLAYERS,
        "drainTimeoutSeconds": AUTOSCALE_DRAIN_TIMEOUT_SECONDS,
        "updatedAt": now_iso(),
    }


def ensure_policies(db, game_types):
    collection = autoscale_collection(db)
    targets = set(game_types)
    targets.add(AUTOSCALE_DEFAULT_GAME_TYPE)
    normalized = []
    seen = set()
    for game_type in sorted(targets):
        doc = collection.find_one_and_update(
            {"_id": autoscale_doc_id("policy", game_type)},
            {"$setOnInsert": default_policy(game_type)},
            upsert=True,
            return_document=ReturnDocument.AFTER,
        )
        # Migrate legacy defaults (2 hub / 4 game) to the new baseline (2 hub / 2 game).
        if (
            doc
            and to_int(doc.get("playersPerStep"), 50) == 50
            and to_int(doc.get("hubPerStep"), 2) == 2
            and to_int(doc.get("gamePerStep"), 4) == 4
            and to_int(doc.get("baseHub"), 2) == 2
            and to_int(doc.get("baseGame"), 4) == 4
            and to_int(doc.get("minHub"), 2) == 2
            and to_int(doc.get("minGame"), 4) == 4
        ):
            collection.update_one(
                {"_id": doc.get("_id")},
                {"$set": {"baseGame": 2, "minGame": 2, "updatedAt": now_iso()}},
            )
            doc["baseGame"] = 2
            doc["minGame"] = 2
        if doc:
            legacy_service_update = {}
            expected_hub = default_game_type_service_name(game_type, "hub")
            expected_game = default_game_type_service_name(game_type, "game")
            if (doc.get("hubService") or "").strip().lower() in {"", "hub"}:
                legacy_service_update["hubService"] = expected_hub
            if (doc.get("gameService") or "").strip().lower() in {"", "game"}:
                legacy_service_update["gameService"] = expected_game
            if legacy_service_update:
                legacy_service_update["updatedAt"] = now_iso()
                collection.update_one({"_id": doc.get("_id")}, {"$set": legacy_service_update})
                doc.update(legacy_service_update)
        # Migrate untouched policy defaults to conservative values suitable for small VPS deployments.
        if doc and matches_previous_default_policy(doc):
            conservative = conservative_policy_update(game_type)
            collection.update_one(
                {"_id": doc.get("_id")},
                {"$set": conservative},
            )
            doc.update(conservative)
        policy = normalize_policy(doc or default_policy(game_type))
        normalized.append(policy)
        seen.add(policy["gameType"])
    for doc in collection.find({"docType": "policy"}):
        game_type = extract_game_type(doc, "policy")
        if game_type in seen:
            continue
        policy = normalize_policy(doc)
        normalized.append(policy)
        seen.add(game_type)
    return normalized


def get_state(db, game_type):
    collection = autoscale_collection(db)
    doc_id = autoscale_doc_id("state", game_type)
    doc = collection.find_one({"_id": doc_id, "docType": "state"}) or collection.find_one({"_id": doc_id}) or {}
    return {
        "lastStep": max(0, to_int(doc.get("lastStep"), 0)),
        "lastScaleUpAt": max(0, to_int(doc.get("lastScaleUpAt"), 0)),
        "lastScaleDownAt": max(0, to_int(doc.get("lastScaleDownAt"), 0)),
    }


def save_state(db, game_type, state):
    doc_id = autoscale_doc_id("state", game_type)
    payload = {
        "docType": "state",
        "gameType": normalize_game_type(game_type),
        "lastStep": max(0, to_int(state.get("lastStep"), 0)),
        "lastScaleUpAt": max(0, to_int(state.get("lastScaleUpAt"), 0)),
        "lastScaleDownAt": max(0, to_int(state.get("lastScaleDownAt"), 0)),
        "updatedAt": now_iso(),
    }
    autoscale_collection(db).update_one({"_id": doc_id}, {"$set": payload}, upsert=True)


def record_event(db, game_type, event_type, payload):
    document = {
        "docType": "event",
        "timestamp": now_iso(),
        "gameType": normalize_game_type(game_type),
        "eventType": event_type,
        "payload": payload,
    }
    autoscale_collection(db).insert_one(document)


def compute_step_with_hysteresis(players, players_per_step, hysteresis_players, last_step):
    step = max(0, last_step)
    while players >= ((step + 1) * players_per_step + hysteresis_players):
        step += 1
    while step > 0 and players < (step * players_per_step - hysteresis_players):
        step -= 1
    return step


def match_registry_doc(container, registry_docs):
    container_id = (container.get("id") or "").lower()
    short_id = container_id[:12]
    container_name = (container.get("name") or "").lower()
    keys = {container_id, short_id, container_name}

    best = None
    best_score = None
    for doc in registry_docs:
        server_id = str(doc.get("_id", "")).strip().lower()
        address = str(doc.get("address", "")).strip().lower()

        score = None
        if server_id and server_id in keys:
            score = 0
        elif address and address in keys:
            score = 1
        if score is None:
            continue
        if best is None or score < best_score:
            best = doc
            best_score = score
    return best


def map_targets_to_server_ids(db, targets):
    if db is None or not targets:
        return {}

    cutoff = now_ms() - AUTOSCALE_STALE_HEARTBEAT_MILLIS
    registry_docs = list(
        db[AUTOSCALE_REGISTRY_COLLECTION].find(
            {
                "status": "online",
                "lastHeartbeat": {"$gte": cutoff},
            }
        )
    )
    mapping = {}
    for target in targets:
        match = match_registry_doc(target, registry_docs)
        if match is None:
            continue
        server_id = str(match.get("_id", "")).strip().lower()
        if not server_id:
            continue
        mapping[target.get("id")] = server_id
    return mapping


def filter_targets_by_server_ids(targets, target_server_map, requested_server_ids):
    if requested_server_ids is None:
        return targets
    if not targets:
        return []
    requested = set((value or "").strip().lower() for value in requested_server_ids if value)
    if not requested:
        return targets
    filtered = []
    for target in targets:
        server_id = target_server_map.get(target.get("id"), "")
        if server_id in requested:
            filtered.append(target)
    return filtered


def fetch_registry_stats(db):
    cutoff = now_ms() - AUTOSCALE_STALE_HEARTBEAT_MILLIS
    cursor = db[AUTOSCALE_REGISTRY_COLLECTION].find(
        {
            "status": "online",
            "lastHeartbeat": {"$gte": cutoff},
        }
    )
    stats = {}
    for doc in cursor:
        kind = classify_kind(doc.get("type"))
        if kind is None:
            continue
        game_type = normalize_game_type(doc.get("group"))
        item = stats.setdefault(
            game_type,
            {
                "onlinePlayers": 0,
                "playersByKind": {"hub": 0, "game": 0},
                "registryByKind": {"hub": [], "game": []},
                "registryCounts": {"hub": 0, "game": 0},
            },
        )
        players = max(0, to_int(doc.get("players"), 0))
        item["onlinePlayers"] += players
        item["playersByKind"][kind] += players
        item["registryByKind"][kind].append(doc)
        item["registryCounts"][kind] += 1
    return stats


def fetch_external_player_overrides(db):
    cutoff = now_ms() - (AUTOSCALE_PLAYERS_TTL_SECONDS * 1000)
    cursor = autoscale_collection(db).find(
        {
            "docType": "players",
            "updatedAtMs": {"$gte": cutoff},
        }
    )
    values = {}
    for doc in cursor:
        game_type = extract_game_type(doc, "players")
        values[game_type] = max(0, to_int(doc.get("onlinePlayers"), 0))
    return values


def list_active_drains(db, game_type, kind):
    cursor = autoscale_collection(db).find(
        {"docType": "drain", "gameType": normalize_game_type(game_type), "kind": kind, "active": True}
    )
    return list(cursor)


def mark_drain_inactive(db, drain_id, reason):
    autoscale_collection(db).update_one(
        {"_id": drain_id},
        {
            "$set": {
                "active": False,
                "endedAt": now_ms(),
                "endedAtIso": now_iso(),
                "endReason": reason,
            }
        },
    )


def start_or_refresh_drain(db, game_type, kind, service, container, registry_doc, timeout_ms):
    normalized_game_type = normalize_game_type(game_type)
    drain_id = f"drain:{normalized_game_type}:{kind}:{container['id'][:12]}"
    started = now_ms()
    payload = {
        "docType": "drain",
        "gameType": normalized_game_type,
        "kind": kind,
        "service": service,
        "containerId": container["id"],
        "containerName": container["name"],
        "active": True,
        "startedAt": started,
        "startedAtIso": now_iso(),
        "expiresAt": started + timeout_ms,
        "updatedAt": started,
    }
    if registry_doc is not None:
        payload["serverId"] = str(registry_doc.get("_id", ""))
        payload["serverAddress"] = registry_doc.get("address")
    autoscale_collection(db).update_one(
        {"_id": drain_id},
        {"$set": payload, "$setOnInsert": {"createdAt": started}},
        upsert=True,
    )
    return drain_id


def update_drain_heartbeat(db, drain_id, players):
    autoscale_collection(db).update_one(
        {"_id": drain_id},
        {
            "$set": {
                "lastSeenAt": now_ms(),
                "lastSeenAtIso": now_iso(),
                "lastSeenPlayers": max(0, players),
            }
        },
    )


def process_drain_scale_down(
    db,
    game_type,
    kind,
    service_name,
    desired_count,
    containers,
    registry_docs,
    state,
    policy,
):
    active_containers = [container for container in containers if is_container_active(container)]
    active_count = len(active_containers)
    container_by_id = {container["id"]: container for container in active_containers}
    registry_map = {container["id"]: match_registry_doc(container, registry_docs) for container in active_containers}

    drains = list_active_drains(db, game_type, kind)
    drain_by_container = {drain.get("containerId"): drain for drain in drains}

    for drain in drains:
        container_id = drain.get("containerId", "")
        if not container_id:
            mark_drain_inactive(db, drain["_id"], "missing_container_id")
            continue
        if container_id not in container_by_id:
            mark_drain_inactive(db, drain["_id"], "container_not_found")

    drains = list_active_drains(db, game_type, kind)
    drain_by_container = {drain.get("containerId"): drain for drain in drains}

    excess = max(0, active_count - desired_count)
    cooldown_ok = (now_ms() - state["lastScaleDownAt"]) >= policy["scaleDownCooldownMs"]

    started_drains = []
    removed_containers = []
    retained_drains = []

    if excess > 0 and cooldown_ok:
        needed_drains = max(0, excess - len(drains))
        if needed_drains > 0:
            candidates = [container for container in active_containers if container["id"] not in drain_by_container]

            def score(container):
                doc = registry_map.get(container["id"])
                mapped = 0 if doc is not None else 1
                players = to_int(doc.get("players"), 1000000) if doc is not None else 1000000
                return (mapped, players, container["name"])

            candidates.sort(key=score)
            for container in candidates[:needed_drains]:
                registry_doc = registry_map.get(container["id"])
                drain_id = start_or_refresh_drain(
                    db,
                    game_type,
                    kind,
                    service_name,
                    container,
                    registry_doc,
                    policy["drainTimeoutMs"],
                )
                started_drains.append({"drainId": drain_id, "container": container["name"]})
                record_event(
                    db,
                    game_type,
                    "drain_started",
                    {
                        "kind": kind,
                        "service": service_name,
                        "container": container["name"],
                        "containerId": container["id"],
                    },
                )

    drains = list_active_drains(db, game_type, kind)

    if len(drains) > excess:
        release = len(drains) - excess
        releasable = sorted(drains, key=lambda value: to_int(value.get("startedAt"), 0), reverse=True)[:release]
        for drain in releasable:
            mark_drain_inactive(db, drain["_id"], "target_increased")
            record_event(
                db,
                game_type,
                "drain_released",
                {
                    "kind": kind,
                    "service": service_name,
                    "container": drain.get("containerName"),
                    "containerId": drain.get("containerId"),
                },
            )

    drains = sorted(list_active_drains(db, game_type, kind), key=lambda value: to_int(value.get("startedAt"), 0))
    for drain in drains:
        container_id = drain.get("containerId", "")
        container = container_by_id.get(container_id)
        if container is None:
            mark_drain_inactive(db, drain["_id"], "container_not_found")
            continue

        registry_doc = registry_map.get(container_id)
        players = max(0, to_int(registry_doc.get("players"), 0)) if registry_doc is not None else 1
        update_drain_heartbeat(db, drain["_id"], players)

        expires_at = to_int(drain.get("expiresAt"), 0)
        timed_out = expires_at > 0 and now_ms() >= expires_at
        if players <= 0 or timed_out:
            reason = "timeout" if timed_out and players > 0 else "empty"
            try:
                stop_and_remove_container(container_id)
            except Exception as exc:
                retained_drains.append(
                    {
                        "container": container["name"],
                        "containerId": container_id,
                        "players": players,
                        "removeError": str(exc),
                    }
                )
                record_event(
                    db,
                    game_type,
                    "container_remove_failed",
                    {
                        "kind": kind,
                        "service": service_name,
                        "container": container["name"],
                        "containerId": container_id,
                        "reason": reason,
                        "players": players,
                        "error": str(exc),
                    },
                )
                continue
            mark_drain_inactive(db, drain["_id"], f"removed_{reason}")
            state["lastScaleDownAt"] = now_ms()
            removed_containers.append(
                {
                    "container": container["name"],
                    "containerId": container_id,
                    "reason": reason,
                    "players": players,
                }
            )
            record_event(
                db,
                game_type,
                "container_removed",
                {
                    "kind": kind,
                    "service": service_name,
                    "container": container["name"],
                    "containerId": container_id,
                    "reason": reason,
                    "players": players,
                },
            )
        else:
            retained_drains.append(
                {
                    "container": container["name"],
                    "containerId": container_id,
                    "players": players,
                }
            )

    return {
        "kind": kind,
        "service": service_name,
        "desired": desired_count,
        "current": active_count,
        "excess": excess,
        "startedDrains": started_drains,
        "retainedDrains": retained_drains,
        "removedContainers": removed_containers,
        "cooldownReady": cooldown_ok,
    }


def run_autoscale_tick(trigger):
    if not AUTOSCALE_ENABLED:
        return {"enabled": False, "reason": "disabled"}

    with autoscale_lock:
        compose_project = detect_compose_project()
        if not compose_project:
            raise RuntimeError("compose project not detected; set COMPOSE_PROJECT")

        db = get_mongo_database()
        stats_by_game_type = fetch_registry_stats(db)
        external_players = fetch_external_player_overrides(db)
        game_types = set(stats_by_game_type.keys()) | set(external_players.keys())
        policies = ensure_policies(db, game_types)
        project_containers = list_project_containers(compose_project, include_all=True)

        summary = {
            "timestamp": now_iso(),
            "trigger": trigger,
            "composeProject": compose_project,
            "results": [],
        }

        for policy in policies:
            game_type = policy["gameType"]
            stats = stats_by_game_type.get(
                game_type,
                {
                    "onlinePlayers": 0,
                    "playersByKind": {"hub": 0, "game": 0},
                    "registryByKind": {"hub": [], "game": []},
                    "registryCounts": {"hub": 0, "game": 0},
                },
            )
            players_source = "registry"
            if game_type in external_players:
                stats = {
                    "onlinePlayers": external_players[game_type],
                    "playersByKind": stats["playersByKind"],
                    "registryByKind": stats["registryByKind"],
                    "registryCounts": stats["registryCounts"],
                }
                players_source = "external"

            state = get_state(db, game_type)
            step = compute_step_with_hysteresis(
                stats["onlinePlayers"],
                policy["playersPerStep"],
                policy["hysteresisPlayers"],
                state["lastStep"],
            )
            state["lastStep"] = step

            desired_hub = policy["baseHub"] + (step * policy["hubPerStep"])
            desired_game = policy["baseGame"] + (step * policy["gamePerStep"])
            desired_hub = max(policy["minHub"], min(policy["maxHub"], desired_hub))
            desired_game = max(policy["minGame"], min(policy["maxGame"], desired_game))

            hub_service = policy["hubService"]
            game_service = policy["gameService"]

            hub_containers = [item for item in project_containers if item["service"] == hub_service]
            game_containers = [item for item in project_containers if item["service"] == game_service]

            current_hub = len([item for item in hub_containers if is_container_active(item)])
            current_game = len([item for item in game_containers if is_container_active(item)])

            up_needed = desired_hub > current_hub or desired_game > current_game
            up_cooldown_ok = (now_ms() - state["lastScaleUpAt"]) >= policy["scaleUpCooldownMs"]
            scale_output = None

            if up_needed and up_cooldown_ok:
                target_hub = max(current_hub, desired_hub)
                target_game = max(current_game, desired_game)
                scale_actions = []

                if target_hub > current_hub:
                    hub_output = run_compose_scale_service(compose_project, hub_service, target_hub)
                    scale_actions.append(
                        {
                            "service": hub_service,
                            "kind": "hub",
                            "from": current_hub,
                            "to": target_hub,
                            "output": hub_output,
                        }
                    )

                if target_game > current_game:
                    game_output = run_compose_scale_service(compose_project, game_service, target_game)
                    scale_actions.append(
                        {
                            "service": game_service,
                            "kind": "game",
                            "from": current_game,
                            "to": target_game,
                            "output": game_output,
                        }
                    )

                if scale_actions:
                    scale_output = scale_actions
                    state["lastScaleUpAt"] = now_ms()
                    record_event(
                        db,
                        game_type,
                        "scale_up",
                        {
                            "actions": scale_actions,
                        },
                    )
                project_containers = list_project_containers(compose_project, include_all=True)
                hub_containers = [item for item in project_containers if item["service"] == hub_service]
                game_containers = [item for item in project_containers if item["service"] == game_service]
                current_hub = len([item for item in hub_containers if is_container_active(item)])
                current_game = len([item for item in game_containers if is_container_active(item)])

            down_hub_result = process_drain_scale_down(
                db,
                game_type,
                "hub",
                hub_service,
                desired_hub,
                hub_containers,
                stats["registryByKind"]["hub"],
                state,
                policy,
            )
            down_game_result = process_drain_scale_down(
                db,
                game_type,
                "game",
                game_service,
                desired_game,
                game_containers,
                stats["registryByKind"]["game"],
                state,
                policy,
            )

            save_state(db, game_type, state)

            project_containers = list_project_containers(compose_project, include_all=True)
            final_hub = len([item for item in project_containers if item["service"] == hub_service and is_container_active(item)])
            final_game = len([item for item in project_containers if item["service"] == game_service and is_container_active(item)])

            metrics_payload = {
                "docType": "metrics",
                "gameType": game_type,
                "onlinePlayers": stats["onlinePlayers"],
                "onlinePlayersSource": players_source,
                "playersByKind": stats["playersByKind"],
                "registryCounts": stats["registryCounts"],
                "desiredServers": {"hub": desired_hub, "game": desired_game},
                "currentContainers": {
                    "hub": final_hub,
                    "game": final_game,
                },
                "step": step,
                "updatedAt": now_iso(),
            }
            autoscale_collection(db).update_one(
                {"_id": autoscale_doc_id("metrics", game_type)},
                {"$set": metrics_payload},
                upsert=True,
            )

            summary["results"].append(
                {
                    "gameType": game_type,
                    "onlinePlayers": stats["onlinePlayers"],
                    "onlinePlayersSource": players_source,
                    "desiredServers": {"hub": desired_hub, "game": desired_game},
                    "currentAfterActions": {"hub": final_hub, "game": final_game},
                    "scaleUpTriggered": bool(scale_output),
                    "scaleUpOutput": scale_output,
                    "downscaleHub": down_hub_result,
                    "downscaleGame": down_game_result,
                }
            )

        return summary


def autoscale_loop():
    print(f"autoscale loop started (interval={AUTOSCALE_INTERVAL_SECONDS}s)")

    if AUTOSCALE_STARTUP_GRACE_SECONDS > 0:
        print(f"autoscale startup grace: waiting {AUTOSCALE_STARTUP_GRACE_SECONDS}s before first tick")
        if autoscale_stop_event.wait(AUTOSCALE_STARTUP_GRACE_SECONDS):
            return

    if AUTOSCALE_STARTUP_WAIT_FOR_READY_SERVICES:
        start = time.time()
        while not autoscale_stop_event.is_set():
            try:
                compose_project = detect_compose_project()
                if compose_project:
                    ready_hub = count_ready_kind_containers(compose_project, "hub")
                    ready_game = count_ready_kind_containers(compose_project, "game")
                    if ready_hub >= 1 and ready_game >= 1:
                        print(
                            "autoscale startup gate satisfied "
                            f"(compose={compose_project}, hub={ready_hub}, game={ready_game})"
                        )
                        break
                    detail = f"waiting for healthy hub/game (hub={ready_hub}, game={ready_game})"
                else:
                    detail = "waiting for compose project detection"
            except Exception as exc:
                detail = f"startup gate check failed: {exc}"

            elapsed = int(max(0, time.time() - start))
            if (
                AUTOSCALE_STARTUP_WAIT_TIMEOUT_SECONDS > 0
                and elapsed >= AUTOSCALE_STARTUP_WAIT_TIMEOUT_SECONDS
            ):
                print(
                    "autoscale startup gate timed out after "
                    f"{AUTOSCALE_STARTUP_WAIT_TIMEOUT_SECONDS}s; continuing ({detail})"
                )
                break

            print(f"autoscale startup gate: {detail}")
            if autoscale_stop_event.wait(5):
                return

    while not autoscale_stop_event.is_set():
        try:
            result = run_autoscale_tick("interval")
            print(f"autoscale tick complete: {json.dumps(result, default=str)}")
        except Exception as exc:
            print(f"autoscale tick failed: {exc}")
        autoscale_stop_event.wait(AUTOSCALE_INTERVAL_SECONDS)


class RolloutHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        message = fmt % args
        print(f"{self.address_string()} - {message}")

    @staticmethod
    def _is_client_disconnect(exc):
        if isinstance(exc, (BrokenPipeError, ConnectionResetError)):
            return True
        if isinstance(exc, OSError):
            return exc.errno in {errno.EPIPE, errno.ECONNRESET}
        return False

    def json_response(self, status_code, payload):
        body = json.dumps(payload).encode("utf-8")
        try:
            self.send_response(status_code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return True
        except Exception as exc:
            if self._is_client_disconnect(exc):
                path = urllib.parse.urlparse(self.path).path
                print(f"{self.address_string()} - client disconnected before response was sent ({path})")
                return False
            raise

    def parse_json_body(self):
        length_raw = self.headers.get("Content-Length", "0")
        try:
            length = max(0, int(length_raw))
        except ValueError:
            length = 0
        raw = self.rfile.read(length) if length > 0 else b"{}"
        text = raw.decode("utf-8", errors="replace").strip() or "{}"
        value = json.loads(text)
        if not isinstance(value, dict):
            raise ValueError("json body must be an object")
        return value

    def authorized(self):
        if not ROLLOUT_TOKEN:
            return True
        provided = (self.headers.get("X-Rollout-Token") or "").strip()
        return bool(provided) and secrets.compare_digest(provided, ROLLOUT_TOKEN)

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/healthz":
            self.json_response(
                200,
                {
                    "ok": True,
                    "autoscaleEnabled": AUTOSCALE_ENABLED,
                },
            )
            return
        if path == "/readyz":
            payload = build_readiness_payload()
            status = 200 if payload.get("ok") else 503
            self.json_response(status, payload)
            return
        if path == "/autoscale/state":
            if not self.authorized():
                self.json_response(401, {"error": "unauthorized"})
                return
            try:
                db = get_mongo_database()
                collection = autoscale_collection(db)
                metrics = list(collection.find({"docType": "metrics"}))
                state = list(collection.find({"docType": "state"}))
                players = list(collection.find({"docType": "players"}))
                drains = list(collection.find({"docType": "drain", "active": True}))
                self.json_response(
                    200,
                    {
                        "timestamp": now_iso(),
                        "metrics": metrics,
                        "state": state,
                        "players": players,
                        "activeDrains": drains,
                    },
                )
            except Exception as exc:
                self.json_response(500, {"error": str(exc)})
            return
        self.json_response(404, {"error": "not found"})

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/restart":
            if not self.authorized():
                self.json_response(401, {"error": "unauthorized"})
                return
            try:
                payload = self.parse_json_body()
            except Exception as exc:
                self.json_response(400, {"error": f"invalid json body: {exc}"})
                return

            requested_mode = normalize_restart_mode(payload.get("mode"))
            if payload.get("mode") is not None and not requested_mode:
                self.json_response(400, {"error": "mode must be one of: restart, recreate, rebuild"})
                return

            services_valid, requested_services = parse_requested_services(payload.get("services"))
            if not services_valid:
                self.json_response(400, {"error": "services must be a string or list of strings"})
                return
            service_valid, single_service = parse_requested_services(payload.get("service"))
            if not service_valid:
                self.json_response(400, {"error": "service must be a string"})
                return
            requested_services = merge_requested_services(requested_services, single_service)
            server_ids_valid, requested_server_ids = parse_requested_server_ids(payload.get("serverIds"))
            if not server_ids_valid:
                self.json_response(400, {"error": "serverIds must be a string or list of strings"})
                return
            server_id_valid, single_server_id = parse_requested_server_ids(payload.get("serverId"))
            if not server_id_valid:
                self.json_response(400, {"error": "serverId must be a string"})
                return
            legacy_server_valid, legacy_server_id = parse_requested_server_ids(payload.get("server"))
            if not legacy_server_valid:
                self.json_response(400, {"error": "server must be a string"})
                return
            requested_server_ids = merge_requested_server_ids(requested_server_ids, single_server_id)
            requested_server_ids = merge_requested_server_ids(requested_server_ids, legacy_server_id)
            if requested_server_ids is not None and not requested_server_ids:
                requested_server_ids = None

            restart_mode = requested_mode or ROLLOUT_RESTART_MODE
            if payload.get("rebuild") is True:
                restart_mode = "rebuild"
            elif payload.get("recreate") is True and restart_mode == "restart":
                restart_mode = "recreate"
            if requested_server_ids is not None and restart_mode != "restart":
                self.json_response(
                    400,
                    {
                        "error": "server targeting requires restart mode",
                        "requestedServerIds": requested_server_ids,
                        "restartMode": restart_mode,
                    },
                )
                return
            timestamp = now_iso()
            try:
                compose_project = detect_compose_project()
                if not compose_project:
                    raise RuntimeError("compose project not detected; set COMPOSE_PROJECT")
                try:
                    plugin_sync = sync_source_rollout_plugins()
                    plugin_validation = validate_rollout_plugins()
                except Exception as exc:
                    self.json_response(
                        409,
                        {
                            "timestamp": timestamp,
                            "error": "plugin sync/validation failed",
                            "detail": str(exc),
                            "pluginDir": ROLLOUT_PLUGIN_DIR,
                            "pluginSourceDir": ROLLOUT_PLUGIN_SOURCE_DIR,
                        },
                    )
                    return
                db = get_mongo_database() if requested_server_ids is not None else None
                project_containers = list_project_containers(compose_project, include_all=True)
                available_targets = resolve_restart_targets(project_containers)
                targets = resolve_restart_targets(project_containers, requested_services)
                if requested_services is not None and not targets:
                    self.json_response(
                        400,
                        {
                            "error": "requested services did not match any restart targets",
                            "requestedServices": requested_services,
                            "availableServices": ordered_target_services(available_targets),
                        },
                    )
                    return
                target_server_map = {}
                if requested_server_ids is not None:
                    available_server_map = map_targets_to_server_ids(db, available_targets)
                    target_server_map = map_targets_to_server_ids(db, targets)
                    targets = filter_targets_by_server_ids(targets, target_server_map, requested_server_ids)
                    if not targets:
                        self.json_response(
                            400,
                            {
                                "error": "requested servers did not match any restart targets",
                                "requestedServerIds": requested_server_ids,
                                "requestedServices": requested_services,
                                "availableServices": ordered_target_services(available_targets),
                                "availableServerIds": sorted(set(available_server_map.values())),
                            },
                        )
                        return
                    selected_ids = {target.get("id") for target in targets}
                    target_server_map = {
                        container_id: server_id
                        for container_id, server_id in target_server_map.items()
                        if container_id in selected_ids
                    }
                target_services = ordered_target_services(targets)
                scaled_to_minimum = []
                for service in target_services:
                    if classify_service_kind(service) not in {"hub", "game"}:
                        continue
                    current_active = len(
                        [
                            item
                            for item in project_containers
                            if item.get("service") == service and is_container_active(item)
                        ]
                    )
                    desired_replicas = desired_service_replicas(project_containers, service)
                    if current_active >= desired_replicas:
                        continue

                    run_compose_up_service(
                        compose_project,
                        service_name=service,
                        replicas=desired_replicas,
                        build=False,
                        force_recreate=False,
                    )
                    ready, ready_detail = wait_for_service_ready(
                        compose_project,
                        service_name=service,
                        expected_count=desired_replicas,
                        timeout_seconds=RESTART_HEALTH_WAIT_SECONDS,
                    )
                    project_containers = list_project_containers(compose_project, include_all=True)
                    if not ready:
                        raise RuntimeError(
                            f"failed to ensure minimum replicas for {service}: {ready_detail}"
                        )
                    scaled_to_minimum.append(
                        {
                            "service": service,
                            "from": current_active,
                            "to": desired_replicas,
                            "detail": ready_detail,
                        }
                    )

                targets = resolve_restart_targets(project_containers, requested_services)
                if requested_server_ids is not None:
                    target_server_map = map_targets_to_server_ids(db, targets)
                    targets = filter_targets_by_server_ids(targets, target_server_map, requested_server_ids)
                    if not targets:
                        self.json_response(
                            409,
                            {
                                "error": "requested server targets became unavailable before restart",
                                "requestedServerIds": requested_server_ids,
                                "availableServerIds": sorted(set(target_server_map.values())),
                            },
                        )
                        return
                    selected_ids = {target.get("id") for target in targets}
                    target_server_map = {
                        container_id: server_id
                        for container_id, server_id in target_server_map.items()
                        if container_id in selected_ids
                    }
                target_services = ordered_target_services(targets)
                restarted = []
                failed = []
                if restart_mode == "restart":
                    for target in targets:
                        service = target.get("service", "")
                        if service == "velocity":
                            ready_hub = count_ready_kind_containers(compose_project, "hub")
                            ready_game = count_ready_kind_containers(compose_project, "game")
                            if ready_hub < 1 or ready_game < 1:
                                failed.append(
                                    {
                                        "name": target["name"],
                                        "reason": (
                                            "skipped velocity restart; requires at least one healthy hub and game "
                                            f"(hub={ready_hub}, game={ready_game})"
                                        ),
                                    }
                                )
                                continue
                        ok, detail = restart_container(target["id"])
                        if ok:
                            ready, ready_detail = wait_for_container_ready(target["id"], RESTART_HEALTH_WAIT_SECONDS)
                            if ready:
                                restarted.append(target["name"])
                            else:
                                failed.append({"name": target["name"], "reason": f"readiness check failed: {ready_detail}"})
                        else:
                            failed.append({"name": target["name"], "reason": detail})
                else:
                    build_images = restart_mode == "rebuild"
                    for service in target_services:
                        if service == "velocity":
                            ready_hub = count_ready_kind_containers(compose_project, "hub")
                            ready_game = count_ready_kind_containers(compose_project, "game")
                            if ready_hub < 1 or ready_game < 1:
                                failed.append(
                                    {
                                        "name": service,
                                        "reason": (
                                            "skipped velocity restart; requires at least one healthy hub and game "
                                            f"(hub={ready_hub}, game={ready_game})"
                                        ),
                                    }
                                )
                                continue

                        replicas = desired_service_replicas(project_containers, service)
                        try:
                            run_compose_up_service(
                                compose_project,
                                service_name=service,
                                replicas=replicas,
                                build=build_images,
                                force_recreate=True,
                            )
                        except Exception as exc:
                            failed.append({"name": service, "reason": str(exc)})
                            continue

                        ready, ready_detail = wait_for_service_ready(
                            compose_project,
                            service_name=service,
                            expected_count=replicas,
                            timeout_seconds=RESTART_HEALTH_WAIT_SECONDS,
                        )
                        if ready:
                            restarted.append(service)
                        else:
                            failed.append({"name": service, "reason": f"readiness check failed: {ready_detail}"})

                        project_containers = list_project_containers(compose_project, include_all=True)

                status_code = 200 if not failed else 500
                target_server_ids = sorted(
                    {
                        target_server_map.get(target.get("id"), "")
                        for target in targets
                        if target_server_map.get(target.get("id"), "")
                    }
                )
                self.json_response(
                    status_code,
                    {
                        "timestamp": timestamp,
                        "composeProject": compose_project,
                        "targetCount": len(targets),
                        "orderedTargets": [target["name"] for target in targets],
                        "targetServices": target_services,
                        "requestedServices": requested_services,
                        "requestedServerIds": requested_server_ids,
                        "targetServerIds": target_server_ids,
                        "restartMode": restart_mode,
                        "targets": [target["name"] for target in targets],
                        "restarted": restarted,
                        "failed": failed,
                        "pluginSync": plugin_sync,
                        "pluginValidation": plugin_validation,
                        "scaledToMinimum": scaled_to_minimum,
                        "restartServiceOrder": list(RESTART_SERVICE_ORDER),
                        "healthWaitSeconds": RESTART_HEALTH_WAIT_SECONDS,
                    },
                )
            except Exception as exc:
                self.json_response(500, {"timestamp": timestamp, "error": str(exc)})
            return

        if path == "/autoscale/tick":
            if not self.authorized():
                self.json_response(401, {"error": "unauthorized"})
                return
            try:
                result = run_autoscale_tick("manual")
                self.json_response(200, result)
            except Exception as exc:
                self.json_response(500, {"error": str(exc)})
            return

        if path == "/autoscale/players":
            if not self.authorized():
                self.json_response(401, {"error": "unauthorized"})
                return
            try:
                payload = self.parse_json_body()
                updates = payload.get("updates")
                if not isinstance(updates, list):
                    updates = [payload]
                accepted = []
                db = get_mongo_database()
                collection = autoscale_collection(db)
                for entry in updates:
                    if not isinstance(entry, dict):
                        continue
                    game_type = normalize_game_type(entry.get("gameType"))
                    players = max(0, to_int(entry.get("onlinePlayers"), 0))
                    source = (entry.get("source") or "web").strip() or "web"
                    updated_at_ms = now_ms()
                    doc_id = autoscale_doc_id("players", game_type)
                    document = {
                        "docType": "players",
                        "gameType": game_type,
                        "onlinePlayers": players,
                        "source": source,
                        "updatedAtMs": updated_at_ms,
                        "updatedAt": now_iso(),
                    }
                    collection.update_one(
                        {"_id": doc_id},
                        {"$set": document},
                        upsert=True,
                    )
                    accepted.append(
                        {
                            "id": doc_id,
                            "gameType": game_type,
                            "onlinePlayers": players,
                            "source": source,
                        }
                    )
                self.json_response(
                    200,
                    {
                        "accepted": accepted,
                    },
                )
            except Exception as exc:
                self.json_response(400, {"error": str(exc)})
            return

        self.json_response(404, {"error": "not found"})


def main():
    if AUTOSCALE_ENABLED:
        thread = threading.Thread(target=autoscale_loop, name="autoscale-loop", daemon=True)
        thread.start()
    server = http.server.ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), RolloutHandler)
    print(
        "control-panel listening on "
        f"{LISTEN_HOST}:{LISTEN_PORT} "
        f"(include_services={sorted(INCLUDE_SERVICES)} include_prefixes={INCLUDE_PREFIXES} "
        f"restart_order={RESTART_SERVICE_ORDER} mode={ROLLOUT_RESTART_MODE} "
        f"health_wait={RESTART_HEALTH_WAIT_SECONDS}s autoscale={AUTOSCALE_ENABLED})"
    )
    server.serve_forever()


if __name__ == "__main__":
    main()

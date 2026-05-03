#!/usr/bin/env python3
import os


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
ROLLOUT_MIN_GAME_REPLICAS = env_int("ROLLOUT_MIN_GAME_REPLICAS", 4, minimum=2)

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
AUTOSCALE_STARTUP_GRACE_SECONDS = env_int("AUTOSCALE_STARTUP_GRACE_SECONDS", 10, minimum=0)
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
AUTOSCALE_HYSTERESIS_PLAYERS = env_int("AUTOSCALE_HYSTERESIS_PLAYERS", 0, minimum=0)
AUTOSCALE_POLICY_DEFAULT_PLAYERS_PER_STEP = env_int("AUTOSCALE_POLICY_DEFAULT_PLAYERS_PER_STEP", 5, minimum=1)
AUTOSCALE_POLICY_DEFAULT_HUB_PER_STEP = env_int("AUTOSCALE_POLICY_DEFAULT_HUB_PER_STEP", 0, minimum=0)
AUTOSCALE_POLICY_DEFAULT_GAME_PER_STEP = env_int("AUTOSCALE_POLICY_DEFAULT_GAME_PER_STEP", 1, minimum=0)
AUTOSCALE_POLICY_DEFAULT_BASE_HUB = env_int("AUTOSCALE_POLICY_DEFAULT_BASE_HUB", 2, minimum=2)
AUTOSCALE_POLICY_DEFAULT_BASE_GAME = env_int("AUTOSCALE_POLICY_DEFAULT_BASE_GAME", 4, minimum=2)
AUTOSCALE_POLICY_DEFAULT_MIN_HUB = env_int("AUTOSCALE_POLICY_DEFAULT_MIN_HUB", 2, minimum=2)
AUTOSCALE_POLICY_DEFAULT_MIN_GAME = env_int("AUTOSCALE_POLICY_DEFAULT_MIN_GAME", 4, minimum=2)
AUTOSCALE_POLICY_DEFAULT_MAX_HUB = env_int("AUTOSCALE_POLICY_DEFAULT_MAX_HUB", 10, minimum=1)
AUTOSCALE_POLICY_DEFAULT_MAX_GAME = env_int("AUTOSCALE_POLICY_DEFAULT_MAX_GAME", 50, minimum=1)

MONGO_URI = os.getenv("MONGO_URI", "").strip()
MONGO_DATABASE = os.getenv("MONGO_DATABASE", "").strip()

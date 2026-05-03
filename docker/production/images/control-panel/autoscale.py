#!/usr/bin/env python3
import datetime
import json
import re
import threading
import time

from pymongo import MongoClient, ReturnDocument

import docker
from config import *


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


def detect_compose_project():
    return docker.detect_compose_project()


def list_project_containers(compose_project, include_all=True):
    return docker.list_project_containers(compose_project, include_all=include_all)


def inspect_container_state(container_id):
    return docker.inspect_container_state(container_id)


def is_container_ready_state(lifecycle_status, health_status):
    return docker.is_container_ready_state(lifecycle_status, health_status)


def run_compose_scale_service(compose_project, service_name, replicas):
    return docker.run_compose_scale_service(compose_project, service_name, replicas)


def stop_and_remove_container(container_id):
    return docker.stop_and_remove_container(container_id)


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


def matches_previous_default_policy(doc, game_type):
    if not doc:
        return False
    hub_service = (doc.get("hubService") or "").strip().lower()
    game_service = (doc.get("gameService") or "").strip().lower()
    expected_hub = default_game_type_service_name(game_type, "hub").lower()
    expected_game = default_game_type_service_name(game_type, "game").lower()
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
        and hub_service in {"", "hub", expected_hub}
        and game_service in {"", "game", expected_game}
    )


def matches_default_policy_before_game_rebalance(doc, game_type):
    if not doc:
        return False
    hub_service = (doc.get("hubService") or "").strip().lower()
    game_service = (doc.get("gameService") or "").strip().lower()
    expected_hub = default_game_type_service_name(game_type, "hub").lower()
    expected_game = default_game_type_service_name(game_type, "game").lower()
    return (
        to_int(doc.get("playersPerStep"), 75) == 75
        and to_int(doc.get("hubPerStep"), 1) == 1
        and to_int(doc.get("gamePerStep"), 1) == 1
        and to_int(doc.get("baseHub"), 2) == 2
        and to_int(doc.get("baseGame"), 2) == 2
        and to_int(doc.get("minHub"), 2) == 2
        and to_int(doc.get("minGame"), 2) == 2
        and to_int(doc.get("maxHub"), 12) == 12
        and to_int(doc.get("maxGame"), 24) == 24
        and to_int(doc.get("hysteresisPlayers"), 20) == 20
        and hub_service in {"", "hub", expected_hub}
        and game_service in {"", "game", expected_game}
    )


def game_rebalance_policy_update(game_type):
    defaults = default_policy(game_type)
    return {
        "playersPerStep": defaults["playersPerStep"],
        "hubPerStep": defaults["hubPerStep"],
        "gamePerStep": defaults["gamePerStep"],
        "baseGame": defaults["baseGame"],
        "minGame": defaults["minGame"],
        "maxHub": defaults["maxHub"],
        "maxGame": defaults["maxGame"],
        "hysteresisPlayers": defaults["hysteresisPlayers"],
        "updatedAt": now_iso(),
    }


def matches_default_policy_before_max_rebalance(doc, game_type):
    if not doc:
        return False
    hub_service = (doc.get("hubService") or "").strip().lower()
    game_service = (doc.get("gameService") or "").strip().lower()
    expected_hub = default_game_type_service_name(game_type, "hub").lower()
    expected_game = default_game_type_service_name(game_type, "game").lower()
    return (
        to_int(doc.get("playersPerStep"), 5) == 5
        and to_int(doc.get("hubPerStep"), 0) == 0
        and to_int(doc.get("gamePerStep"), 1) == 1
        and to_int(doc.get("baseHub"), 2) == 2
        and to_int(doc.get("baseGame"), 4) == 4
        and to_int(doc.get("minHub"), 2) == 2
        and to_int(doc.get("minGame"), 4) == 4
        and to_int(doc.get("maxHub"), 12) == 12
        and to_int(doc.get("maxGame"), 24) == 24
        and to_int(doc.get("hysteresisPlayers"), 0) == 0
        and hub_service in {"", "hub", expected_hub}
        and game_service in {"", "game", expected_game}
    )


def max_rebalance_policy_update(game_type):
    defaults = default_policy(game_type)
    return {
        "maxHub": defaults["maxHub"],
        "maxGame": defaults["maxGame"],
        "updatedAt": now_iso(),
    }


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
        "scaleDownCooldownSeconds": AUTOSCALE_SCALE_DOWN_COOLDOWN_SECONDS,
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
        if doc and matches_previous_default_policy(doc, game_type):
            conservative = conservative_policy_update(game_type)
            collection.update_one(
                {"_id": doc.get("_id")},
                {"$set": conservative},
            )
            doc.update(conservative)
        if doc and matches_default_policy_before_game_rebalance(doc, game_type):
            rebalance = game_rebalance_policy_update(game_type)
            collection.update_one(
                {"_id": doc.get("_id")},
                {"$set": rebalance},
            )
            doc.update(rebalance)
        if doc and matches_default_policy_before_max_rebalance(doc, game_type):
            rebalance = max_rebalance_policy_update(game_type)
            collection.update_one(
                {"_id": doc.get("_id")},
                {"$set": rebalance},
            )
            doc.update(rebalance)
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

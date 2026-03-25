# Docker Bootstrap

This project now uses a generic Docker runtime for both game and hub servers.

## Quick Start

1. Build plugin jars:
   ```bash
   ./gradlew shadowAll
   ```
   This also copies `Hypixel.jar`, `MurderMystery.jar`, `HypixelBuild.jar`, and `HypixelProxy.jar` to `docker/plugins/` for Docker mounts.
2. Create/edit local secrets in `.env` and set strong passwords (single source of truth for credentials).
3. Edit shared (non-secret) runtime settings in:
   ```text
   docker/config.json
   ```
4. Start the stack:
   ```bash
   docker compose up --build
   ```

Services:
- `velocity` exposes `25565` to your host.
- `hub` and `game` are discovered automatically through Mongo `server_registry` heartbeats.
- `build` runs a staff-only build backend and is discovered through Mongo `server_registry` with `_id=build`.
- `mongo` and `redis` are started automatically with authentication enabled.
- `control-panel` is internal-only (no published host port) and handles restart webhooks for `/update`.
- `control-panel` also runs the autoscale loop every minute (V2 drain-first downscale).
- Mongo host port binds to `127.0.0.1` by default (not public on VPS scans).
- Redis is internal-only in Compose (no host port published).
- Paper defaults to `1.8.9`; if no build exists from the API, bootstrap falls back to the latest available `1.8.x` build automatically.
- Proxy `/update` webhook env passthrough is available via `.env`:
  - `ROLLOUT_WEBHOOK_URL` (defaults to `http://control-panel:8080/restart`)
  - `ROLLOUT_WEBHOOK_TOKEN` (optional auth token header `X-Rollout-Token`)
  - `ROLLOUT_INCLUDE_SERVICES` (default `velocity,build`)
  - `ROLLOUT_INCLUDE_PREFIXES` (default `hub,game` to include `hub*`/`game*`)
  - `ROLLOUT_RESTART_SERVICE_ORDER` (default `hub,game,build,velocity`)
  - `ROLLOUT_RESTART_MODE` (default `restart`; options: `restart`, `recreate`, `rebuild`)
  - `ROLLOUT_MIN_HUB_REPLICAS` (default `2`; rollout enforces at least this many `hub` replicas)
  - `ROLLOUT_MIN_GAME_REPLICAS` (default `2`; rollout enforces at least this many `game` replicas)
  - `ROLLOUT_RESTART_HEALTH_WAIT_SECONDS` (default `180`)
- Autoscaler defaults in `.env`:
  - `AUTOSCALE_ENABLED=true`
  - `AUTOSCALE_INTERVAL_SECONDS=60`
  - `AUTOSCALE_STARTUP_GRACE_SECONDS=60` (delay before the first autoscale tick)
  - `AUTOSCALE_STARTUP_WAIT_FOR_READY_SERVICES=true` (wait for at least one healthy `hub` and `game`)
  - `AUTOSCALE_STARTUP_WAIT_TIMEOUT_SECONDS=300` (max wait before continuing autoscale anyway)
  - `AUTOSCALE_DEFAULT_GAME_TYPE=murdermystery`
  - `AUTOSCALE_COLLECTION=autoscale` (single collection for policy/state/metrics/drain/events)
  - `AUTOSCALE_PLAYERS_TTL_SECONDS=180` (external player override freshness window)
  - `AUTOSCALE_DRAIN_TIMEOUT_SECONDS=240`
  - `AUTOSCALE_SCALE_UP_COOLDOWN_SECONDS=90`
  - `AUTOSCALE_SCALE_DOWN_COOLDOWN_SECONDS=300`
  - `AUTOSCALE_HYSTERESIS_PLAYERS=20`
  - `AUTOSCALE_POLICY_DEFAULT_PLAYERS_PER_STEP=75`
  - `AUTOSCALE_POLICY_DEFAULT_HUB_PER_STEP=1`
  - `AUTOSCALE_POLICY_DEFAULT_GAME_PER_STEP=1`
  - `AUTOSCALE_POLICY_DEFAULT_BASE_HUB=2`
  - `AUTOSCALE_POLICY_DEFAULT_BASE_GAME=2`
  - `AUTOSCALE_POLICY_DEFAULT_MIN_HUB=2`
  - `AUTOSCALE_POLICY_DEFAULT_MIN_GAME=2`
  - `AUTOSCALE_POLICY_DEFAULT_MAX_HUB=12`
  - `AUTOSCALE_POLICY_DEFAULT_MAX_GAME=24`

Startup dependency order in `docker-compose.yml`:
- `control-panel` waits for healthy `mongo` and `redis`.
- `control-panel` health uses `/readyz` (Mongo ping + Docker daemon ping + compose project detection).
- `hub` and `game` wait for healthy `mongo`, `redis`, and `control-panel`.
- `build` waits for healthy `mongo`, `redis`, and `control-panel`.
- `velocity` waits for healthy `hub` and `game` (so proxy starts only after hub/game availability).
- Autoscale starts after startup gating (grace delay and optional wait for healthy `hub` + `game`).

`/update` rollout behavior:
- Proxy `/update` posts to control-panel webhook (`/restart` by default).
- Restart mode is controlled by `ROLLOUT_RESTART_MODE`:
  - `restart`: restart existing containers in-place.
  - `recreate`: `docker compose up -d --no-deps --force-recreate` per target service.
  - `rebuild`: same as `recreate` plus `--build` (use this for Dockerfile/entrypoint changes).
- Restart targets are ordered by `ROLLOUT_RESTART_SERVICE_ORDER` and each container is waited to be running/healthy before continuing.
- `velocity` restart is skipped unless at least one healthy `hub` and one healthy `game` container are available.

## Secure MongoDB Compass Access (VPS)

Recommended approach: keep MongoDB private and use an SSH tunnel from your local machine.

1. Keep `.env` defaults for `MONGO_BIND_IP=127.0.0.1`.
2. Open a tunnel from your computer:
   ```bash
   ssh -L 27017:127.0.0.1:27017 <user>@<vps-host>
   ```
3. In MongoDB Compass, connect to:
   - Host: `127.0.0.1`
   - Port: `27017`
   - Username/password: `MONGO_APP_USERNAME` / `MONGO_APP_PASSWORD` from `.env`
   - Authentication Database: `MONGO_APP_DATABASE`

Note: `MONGO_APP_PASSWORD` is embedded in a Mongo URI in Compose; use URL-safe characters or percent-encode special characters.

This avoids exposing MongoDB to the public internet while still allowing external administration.

## Map Layout

Map metadata is configured in MongoDB:

```text
collection: maps
document _id: murdermystery
```

The document supports per-server-type rule sections and game-specific map settings, for example:

```json
{
  "_id": "murdermystery",
  "gameTypes": {
    "murdermystery": {
      "serverTypes": {
        "MURDER_MYSTERY_HUB": {
          "gamerules": {
            "hungerLoss": false,
            "healthLoss": false,
            "vanillaAchievements": false,
            "keepInventory": true,
            "inventoryMoveLocked": true,
            "weatherCycle": false,
            "blockBreak": false,
            "doDaylightCycle": false,
            "doMobSpawning": false,
            "randomTickSpeed": 0
          }
        },
        "MURDER_MYSTERY": {
          "gamerules": {
            "hungerLoss": false,
            "healthLoss": false,
            "vanillaAchievements": false,
            "keepInventory": true,
            "inventoryMoveLocked": true,
            "weatherCycle": false,
            "blockBreak": false
          }
        }
      },
      "rewards": {
        "goldPickupTokens": 10,
        "survive30SecondsTokens": 50,
        "murdererKillTokens": 100
      },
      "activeMap": "hub",
      "rotation": ["hub"],
      "maps": [
        {
          "name": "Hub",
          "worldDirectory": "hub",
          "nightTime": false,
          "spawns": [],
          "dropItem": []
        }
      ]
    }
  }
}
```

Notes:
- `gameTypes.<gameType>.serverTypes.<SERVER_TYPE>.gamerules` is the single merged rules section.
- Pseudo-gamerules (`hungerLoss`, `healthLoss`, `vanillaAchievements`, `keepInventory`, `inventoryMoveLocked`, `weatherCycle`, `blockBreak`) are mapped to gameplay toggles internally.
- Standard gamerules are written through Bukkit `World#setGameRuleValue(rule, value)`.
- The backend resolves the map game key from `server.group` (for example `murdermystery`).
- `gameTypes.<gameType>.maps[].worldDirectory` stores the world folder name under `maps/<gameType>/`.
- `gameTypes.<gameType>.maps[].name` is auto-formatted from `worldDirectory` for display (`archives_top_floor` -> `Archives Top Floor`).
- Map classification is name-based: contains `hub` => hub map; any other name => game map.
- `gameTypes.murdermystery.rewards` is read from `maps` and applied to token rewards.
- Core startup seeds a default `murdermystery` map document when missing.
  It also syncs `maps/<gameType>/` directory names into `maps` on startup.

World templates are grouped by game type:

```text
docker/maps/<gameType>/<mapName>/
```

Example currently in this repo:

```text
docker/maps/murdermystery/hub/
```
`hub` and `game` containers resolve `MAP_NAME` from Mongo `maps` when unset.
If Mongo map resolution is empty/unavailable at boot, entrypoint falls back to local map directory selection under `docker/maps/<gameType>/`.
Fallback behavior is server-kind aware:
- `hub` servers prefer map directories containing `hub`.
- `game` servers prefer non-`hub` map directories.
With `MAP_APPLY_MODE=link`, they run that exact map directory directly via symlink (no clone):

```text
/data/${MAP_NAME} -> /maps/${GAME_TYPE}/${MAP_NAME}
level-name=${MAP_NAME}
```

To export a world you built with Multiverse into local map storage, use the manual workflow in [docker/maps/README.md](./maps/README.md) (unload world, copy, then load world again).

The `build` container can also download and extract a world archive into `/data/world` at boot:
- `WORLD_ARCHIVE_URL`
- `WORLD_ARCHIVE_FORCE_EXTRACT` (`true`/`false`)

Build server world data is persisted in the Docker named volume `build-data` (mounted at `/data`), so custom worlds created on the build server survive container restarts/recreates.

Hub and game Paper servers also auto-download `Citizens.jar` for hub/Profile/Click-to-Play NPC support.
- Default source: Citizens 2.0.30 build `b2924` (1.8-compatible).
- Override source with `CITIZENS_URL` if you want a different build.

It also auto-downloads these plugins when `SERVER_TYPE=BUILD`:
- `Multiverse-Core.jar`
- `WorldEdit.jar`
- `VoxelSniper.jar`
- `VoidGenerator.jar`

Build plugin download URLs are pinned to ForgeCDN in the Paper entrypoint script.

## Restart Behavior

Paper servers are configured at boot with:

```text
settings.restart-script: /usr/local/bin/paper-restart.sh
```

The restart script terminates PID 1 so Docker can restart the same container instance
(for game servers this works with `restart: unless-stopped` in `docker-compose.yml`).

## Immutable Runtime Config

Runtime configuration is split into two files:

- `.env` for secrets and host bindings (`MONGO_*`, `REDIS_*`)
- `docker/config.json` for non-secret plugin/runtime settings

`docker/config.json` is still used for restart-required/non-repeatable shared values:

```text
docker/config.json
```

It is copied into plugin data folders on startup:
- `/data/plugins/Hypixel/config.json` (Paper servers)
- `/server/plugins/hypixelproxy/config.json` (Velocity proxy plugin)

Do not store database credentials in `docker/config.json`; keep secrets in `.env`.
`mongo.uri` and `redis.password` in `docker/config.json` are placeholder fallbacks and are overridden from `.env` in Docker.

Menu/selector database refresh cadence is configurable in `docker/config.json` under `menus`:
- `registryDataRefreshTicks`
- `gameMenuRefreshTicks`
- `lobbySelectorRefreshTicks`
- `lobbySelectorDataRefreshTicks`

Murder Mystery token rewards are configurable in Mongo `maps` (`_id: murdermystery`) under `gameTypes.murdermystery.rewards`.

Gameplay toggles and gamerules are configured in Mongo `maps` (`_id: murdermystery`) under `gameTypes.murdermystery.serverTypes.<SERVER_TYPE>.gamerules` (not in `docker/config.json`).

Proxy MOTD is configurable in `docker/config.json`:

```json
"motd": {
  "collection": "proxy_settings",
  "documentId": "motd",
  "field": "text",
  "cacheTtlSeconds": 5
}
```

The proxy reads one Mongo document (default: collection `proxy_settings`, id `motd`) and supports:

```json
{
  "_id": "motd",
  "motdFirstLine": "&aHypixel Copy",
  "motdSecondLine": "",
  "maintenanceMotdFirstLine": "&cMaintenance mode",
  "maintenanceMotdSecondLine": "",
  "playerCount": 200,
  "maintenanceEnabled": false,
  "text": "&aHypixel Copy"
}
```

Notes:
- `motdFirstLine`/`motdSecondLine` are used directly when present.
- `text` (or whatever `motd.field` is set to) is a fallback single-field MOTD split into two lines.
- `playerCount` controls the ping max player count shown in the server list (`maxPlayers` is still accepted as a compatibility alias).
- Values are cached in-memory by the proxy for `cacheTtlSeconds` (minimum 5 seconds), and only one refresh runs at a time to avoid ping-driven Mongo spam.

On startup, plugins now proactively create required collections so they appear immediately in Mongo tooling:
- Core: `profiles`, `leaderboards`, `punishments`, `murdermystery_role_chances`, `knife_skins`, `server_registry`, `maps`
- Proxy: `proxy_settings` (or configured `motd.collection`), `friends`, `server_registry`, `maps`, `autoscale`
- Proxy also seeds the MOTD document on first run using `$setOnInsert`.

Server icon loading:
- Keep `proxy.iconFile` as `server-icon.png` (default) or set an absolute path in config.
- The proxy now checks these locations for relative icon paths:
  - `/server/plugins/hypixelproxy/<iconFile>`
  - `/server/plugins/<iconFile>`
  - `/server/<iconFile>`
- In Docker, place your icon at `docker/proxy/server-icon.png`; the velocity entrypoint copies it to both `/server/server-icon.png` and `/server/plugins/hypixelproxy/server-icon.png` at boot.

Backend authentication mode (Paper):
- Backends are forced to `online-mode=false` in `server.properties`.
- `settings.bungeecord=true` is enforced in `spigot.yml` for legacy forwarding via Velocity.
- Nether/End generation is disabled at bootstrap:
  - `server.properties: allow-nether=false`
  - `bukkit.yml: settings.allow-end=false`
- `max-players` is applied from `SERVER_MAX_PLAYERS`; hub defaults to `100` and build defaults to `10` if unset. Set this per service/game type (for example Murder Mystery game servers use `SERVER_MAX_PLAYERS=16`).
- Server registry heartbeats also honor `SERVER_MAX_PLAYERS` for the published `maxPlayers` field in Mongo.

## Scaling Hubs/Games

Autoscaler V2 now handles this automatically:
- Every minute it aggregates online players per game type from Mongo `server_registry`.
- It stores policy/state/metrics/drain/events in one collection: `autoscale` (using `docType` fields).
- Optional external input: post `docType=players` updates to `control-panel` and those values are used for scaling while fresh.
- Default baseline is `1` hub server and `1` game server.
- For every +75 players it adds +1 game server and +1 hub server (policy-driven).
- Existing untouched default policy docs are auto-migrated to this conservative profile.
- Scale-down is drain-first: selected servers are marked as `docType=drain`, excluded by proxy routing, and removed when empty or timeout is reached.

External players override API (internal-only):

```bash
docker compose exec -T control-panel wget -qO- \
  --header='Content-Type: application/json' \
  --header='X-Rollout-Token: '"${ROLLOUT_WEBHOOK_TOKEN}" \
  --post-data='{"gameType":"murdermystery","onlinePlayers":120,"source":"web"}' \
  http://127.0.0.1:8080/autoscale/players
```

Manual autoscale tick:

```bash
docker compose exec -T control-panel wget -qO- \
  --header='X-Rollout-Token: '"${ROLLOUT_WEBHOOK_TOKEN}" \
  --post-data='{}' \
  http://127.0.0.1:8080/autoscale/tick
```

Manual override is still available:

```bash
docker compose up --build --scale hub=1 --scale game=2
```

Each Paper container auto-generates identity from its runtime environment and sends heartbeat updates to Mongo.

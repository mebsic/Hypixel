# Maps Directory Contract

This folder now stores only world template files.

Map metadata (rotation, spawns, rewards, per-server-type gamerules) is stored in MongoDB:

```text
collection: maps
document _id: murdermystery
```

`CorePlugin` ensures the collection exists and seeds a default Murder Mystery document on startup.
Build-server commands (`/sethubspawn`, `/addspawn`) update this Mongo document directly.
Map entries store `gameTypes.<gameType>.maps[].worldDirectory` (under `maps/<gameType>/<worldDirectory>`).
Display `name` is generated from `worldDirectory` (for example `archives_top_floor` -> `Archives Top Floor`).

Example document shape:

```json
{
  "_id": "murdermystery",
  "gameTypes": {
    "murdermystery": {
      "serverTypes": {
        "MURDER_MYSTERY_HUB": {
          "gamerules": {
            "hungerLoss": false,
            "healthLoss": false
          }
        },
        "MURDER_MYSTERY": {
          "gamerules": {
            "hungerLoss": false,
            "healthLoss": false
          }
        }
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

Use one root folder per game type, then one folder per map/world template:

```text
docker/maps/<gameType>/<mapName>/
```

Current Murder Mystery world template in this repo:

```text
docker/maps/murdermystery/hub/
```

The Paper container reads:
- `GAME_TYPE`
- `MAP_NAME` (optional; auto-resolved from Mongo when empty)
- `WORLD_NAME` (defaults to `world`)
- `MAP_APPLY_MODE` (`copy` or `link`)

Map type rule:
- If `worldDirectory` (or map name fallback) contains `hub` (case-insensitive), it is treated as a hub map.
- Any other `worldDirectory` is treated as a game map.

Bootstrap resolves:

```text
/maps/${GAME_TYPE}/${MAP_NAME}
```

Copy mode (`MAP_APPLY_MODE=copy`) copies it into:

```text
/data/${WORLD_NAME}
```

Link mode (`MAP_APPLY_MODE=link`) creates a symlink and runs the map as the live world directory directly (no clone):

```text
/data/${MAP_NAME} -> /maps/${GAME_TYPE}/${MAP_NAME}
level-name=${MAP_NAME}
```

Typical workflow from build server to local map storage:

```bash
# 1) Join the build server and open Game/Hub edit menu for your map.
# 2) Click "Export World".
#    The plugin will save + unload the edited world, copy it to:
#    /maps/<gameType>/<worldDirectory>
#    then reload the world and move you back into it.
```

Manual fallback (if needed):

```bash
mkdir -p docker/maps/<gameType>
docker compose cp build:/data/<worldDirectory> docker/maps/<gameType>/
```

# Hypixel Recreation

Open-source Hypixel recreation built using Paper and Velocity with core services, games, and Docker for deployment.

## Important Note

This is an unofficial project. It is not affiliated with or endorsed by Hypixel Inc.

## Project Structure

- `core`: Shared Paper plugin services (profiles, ranks, punishments, menus, Mongo/Redis integration, server registry, queue support).
- `murdermystery`: Murder Mystery hub and game plugin logic.
- `build`: Build server plugin with map editing and export helpers.
- `proxy`: Velocity proxy plugin (routing, parties, friends, chat channels, maintenance, update hooks).
- `docker`: Local deployment stack. `docker/production` stores runtime assets and `docker/development` stores build-time plugin/map outputs.

## Building

Run:

```bash
./gradlew shadowAll
```

This builds shaded plugin artifacts and copies runtime `.jar` files into `docker/development/plugins/`:
- `Hypixel.jar`
- `MurderMystery.jar`
- `HypixelBuild.jar`
- `HypixelProxy.jar`

## Stack

- Java (Paper modules target Java 8, proxy targets Java 17)
- Gradle (Kotlin DSL + Shadow)
- Paper
- Velocity
- MongoDB
- Redis
- Docker Compose

## Quick Start (Docker)

1. Build plugin jars:
   ```bash
   ./gradlew shadowAll
   ```
2. Set secrets in `.env` (Docker Compose injects these into containers as environment variables).
3. Optionally edit non-secret runtime settings in `docker/production/config.json` (for example menus/MOTD/proxy defaults).  
   You do not need to edit `mongo.uri` or `redis.password` because entrypoints override them from `.env` when starting.
4. Start the stack:
   ```bash
   docker compose up --build
   ```
5. Connect to `localhost:25565`.

`control-panel` runs a BusyBox shell pre-start step that copies jars from `docker/development/plugins/` into `docker/production/plugins/` and verifies required runtime jars before the API starts.

## `.env` Template

Create a `.env` file in the repository root using these placeholders:

```env
# Image versions
MONGO_VERSION=8.x
REDIS_VERSION=8.x

# MongoDB credentials
MONGO_ROOT_USERNAME=your_mongo_root_user
MONGO_ROOT_PASSWORD=your_mongo_root_password
MONGO_APP_DATABASE=your_app_database
MONGO_APP_USERNAME=your_app_mongo_user
MONGO_APP_PASSWORD=your_app_mongo_password

# Redis settings
REDIS_PASSWORD=your_redis_password
REDIS_DATABASE=0

# Deployment/rollout settings
ROLLOUT_WEBHOOK_TOKEN=your_rollout_webhook_token
ROLLOUT_RESTART_MODE=rebuild
ROLLOUT_UPDATE_SERVICES=murder-mystery-hub,murder-mystery-game,build

# Network binding
MONGO_BIND_IP=0.0.0.0
MONGO_PORT=27017
REDIS_BIND_IP=127.0.0.1
REDIS_PORT=6379
```

Use strong unique values locally and never commit real secrets.

## Documentation

- [Getting Started with Docker](https://github.com/mebsic/Hypixel/wiki/Getting-Started-with-Docker)
- [Creating and Configuring Maps](https://github.com/mebsic/Hypixel/wiki/Creating-and-Configuring-Maps)

## License

This project is licensed under the GNU General Public License v3.0.
See [LICENSE](LICENSE).

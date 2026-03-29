#!/usr/bin/env bash
set -euo pipefail

mongo_service_user="${MONGO_APP_USERNAME:-}"
mongo_service_password="${MONGO_APP_PASSWORD:-}"
mongo_service_database="${MONGO_APP_DATABASE:-hypixel}"

if [[ -z "${mongo_service_user}" || -z "${mongo_service_password}" ]]; then
  echo "Mongo service credentials were not provided; skipping Mongo user creation."
  exit 0
fi

mongosh --quiet \
  --username "${MONGO_INITDB_ROOT_USERNAME}" \
  --password "${MONGO_INITDB_ROOT_PASSWORD}" \
  --authenticationDatabase admin <<'EOS'
const serviceUser = process.env.MONGO_APP_USERNAME;
const servicePassword = process.env.MONGO_APP_PASSWORD;
const serviceDatabase = process.env.MONGO_APP_DATABASE || "hypixel";
const serviceDb = db.getSiblingDB(serviceDatabase);

if (!serviceUser || !servicePassword) {
  print("Mongo service credentials were not provided; skipping Mongo user creation.");
  quit(0);
}

if (!serviceDb.getUser(serviceUser)) {
  serviceDb.createUser({
    user: serviceUser,
    pwd: servicePassword,
    roles: [{ role: "readWrite", db: serviceDatabase }]
  });
}
EOS

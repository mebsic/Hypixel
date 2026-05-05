#!/usr/bin/env python3
from urllib.parse import urlparse

from pymongo import MongoClient
from pymongo.errors import OperationFailure

import autoscale
import config


def _mongo_host_port():
    parsed = urlparse(autoscale.MONGO_URI or "")
    if not parsed.hostname:
        return "mongo", 27017
    return parsed.hostname, parsed.port or 27017


def ensure_app_user():
    database = config.MONGO_APP_DATABASE or autoscale.MONGO_DATABASE
    username = config.MONGO_APP_USERNAME
    password = config.MONGO_APP_PASSWORD

    if not database or not username or not password:
        print("mongo bootstrap skipped: app database/user/password not fully configured")
        return
    if not config.MONGO_ROOT_USERNAME or not config.MONGO_ROOT_PASSWORD:
        print("mongo bootstrap skipped: root credentials not configured")
        return

    host, port = _mongo_host_port()
    client = MongoClient(
        host=host,
        port=port,
        username=config.MONGO_ROOT_USERNAME,
        password=config.MONGO_ROOT_PASSWORD,
        authSource=config.MONGO_ROOT_AUTH_DATABASE,
        serverSelectionTimeoutMS=5000,
        connectTimeoutMS=3000,
        socketTimeoutMS=5000,
    )
    try:
        db = client[database]
        try:
            db.command("createUser", username, pwd=password, roles=[{"role": "readWrite", "db": database}])
            print(f"mongo bootstrap created app user {username!r} for database {database!r}")
        except OperationFailure as exc:
            if exc.code != 51003 and "already exists" not in str(exc):
                raise
            db.command("updateUser", username, pwd=password, roles=[{"role": "readWrite", "db": database}])
            print(f"mongo bootstrap updated app user {username!r} for database {database!r}")
    finally:
        client.close()

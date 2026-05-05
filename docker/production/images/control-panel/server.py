#!/usr/bin/env python3
import http.server
import threading

import autoscale
import config
import mongo_app_user
from API import RolloutHandler


def main():
    if config.MONGO_BOOTSTRAP_APP_USER:
        mongo_app_user.ensure_app_user()
    if config.AUTOSCALE_ENABLED:
        thread = threading.Thread(target=autoscale.autoscale_loop, name="autoscale-loop", daemon=True)
        thread.start()
    server = http.server.ThreadingHTTPServer((config.LISTEN_HOST, config.LISTEN_PORT), RolloutHandler)
    print(
        "control-panel listening on "
        f"{config.LISTEN_HOST}:{config.LISTEN_PORT} "
        f"(include_services={sorted(config.INCLUDE_SERVICES)} include_prefixes={config.INCLUDE_PREFIXES} "
        f"restart_order={config.RESTART_SERVICE_ORDER} mode={config.ROLLOUT_RESTART_MODE} "
        f"health_wait={config.RESTART_HEALTH_WAIT_SECONDS}s autoscale={config.AUTOSCALE_ENABLED})"
    )
    server.serve_forever()


if __name__ == "__main__":
    main()

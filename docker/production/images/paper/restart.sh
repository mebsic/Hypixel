#!/usr/bin/env bash
set -euo pipefail

echo "[restart] Requesting container restart for this Paper server..."

# In Docker, PID 1 is the Paper JVM process because entrypoint execs java.
# Spigot/Paper calls this script on /restart, so terminating PID 1 lets the
# container supervisor restart this specific server instance.
kill -TERM 1

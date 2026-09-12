#!/usr/bin/env bash
# Restarts the backend detached from the calling shell's process group.
cd "$(dirname "$0")"
LOG="${1:-/tmp/codeatlas-boot.log}"
fuser -k 8090/tcp 2>/dev/null
for i in {1..25}; do ss -tln 2>/dev/null | grep -q ':8090 ' || break; sleep 1; done
setsid nohup ./run-backend.sh > "$LOG" 2>&1 < /dev/null &
disown
for i in {1..90}; do
  if grep -q "Started CodeAtlasApplication" "$LOG" 2>/dev/null; then echo "STARTED after ${i}s"; exit 0; fi
  if grep -q "APPLICATION FAILED TO START\|BUILD FAILURE" "$LOG" 2>/dev/null; then echo "FAILED"; tail -25 "$LOG"; exit 1; fi
  sleep 1
done
echo "TIMEOUT"; tail -20 "$LOG"; exit 1

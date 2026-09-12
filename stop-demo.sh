#!/usr/bin/env bash
# Stops everything started by start-demo.sh.
cd "$(dirname "$0")"
pkill -f "cloudflared tunnel" 2>/dev/null; pkill -f "ngrok http" 2>/dev/null; echo "tunnel stopped"
fuser -k 4200/tcp 2>/dev/null && echo "frontend stopped"
fuser -k 8090/tcp 2>/dev/null && echo "backend stopped"
docker compose stop >/dev/null 2>&1 && echo "database stopped (data preserved)"
echo "Done. Run ./start-demo.sh to bring it back."

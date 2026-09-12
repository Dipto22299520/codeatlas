#!/usr/bin/env bash
# Starts everything needed for the CodeAtlas demo, from a cold laptop.
#   ./start-demo.sh            local only  (http://localhost:4200)
#   ./start-demo.sh --tunnel   also opens a public Cloudflare URL
set -uo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
LOGS="$PWD/.logs"; mkdir -p "$LOGS"
TUNNEL=0; [[ "${1:-}" == "--tunnel" ]] && TUNNEL=1

say() { echo; echo "=== $1"; }

say "1/5  Database"
if ! docker ps --filter name=codeatlas-db --filter status=running -q | grep -q .; then
  docker compose up -d >/dev/null 2>&1
fi
for i in {1..60}; do
  docker exec codeatlas-db pg_isready -U codeatlas -d codeatlas >/dev/null 2>&1 && break
  sleep 1
done
docker exec codeatlas-db pg_isready -U codeatlas -d codeatlas >/dev/null 2>&1 \
  && echo "     PostgreSQL ready on :55432" \
  || { echo "     FAILED: database did not start"; exit 1; }

say "2/5  Backend"
fuser -k 8090/tcp 2>/dev/null; sleep 2
setsid nohup ./run-backend.sh > "$LOGS/backend.log" 2>&1 < /dev/null & disown
for i in {1..120}; do
  grep -q "Started CodeAtlasApplication" "$LOGS/backend.log" 2>/dev/null && break
  grep -qE "APPLICATION FAILED|BUILD FAILURE" "$LOGS/backend.log" 2>/dev/null \
    && { echo "     FAILED - see $LOGS/backend.log"; tail -15 "$LOGS/backend.log"; exit 1; }
  sleep 1
done
echo "     Backend ready on :8090"

say "3/5  Frontend"
fuser -k 4200/tcp 2>/dev/null; sleep 2
( cd frontend && setsid nohup npx ng serve --host 0.0.0.0 --port 4200 \
    > "$LOGS/frontend.log" 2>&1 < /dev/null & disown )
for i in {1..120}; do
  grep -q "Local:" "$LOGS/frontend.log" 2>/dev/null && break
  sleep 1
done
echo "     Frontend ready on :4200"

say "4/5  Index the estate"
RESULT=$(curl -s --max-time 300 -u owner:owner-demo -X POST http://localhost:8090/api/refresh \
         -H 'Content-Type: application/json' -d '{"scope":"full"}')
echo "$RESULT" | python3 -c "
import json,sys
r=json.load(sys.stdin); c=r.get('counts',{})
print('     refresh:', r['state'], '| nodes:', c.get('nodes'), '| edges:', c.get('edges'))
if r.get('failureReason'): print('     reason:', r['failureReason'][:160])
" 2>/dev/null || echo "     refresh response unreadable"

if [[ $TUNNEL -eq 1 ]]; then
  say "5/5  Public tunnel"
  pkill -f "cloudflared tunnel" 2>/dev/null; sleep 1
  rm -f "$LOGS/tunnel.log"
  setsid nohup ~/bin/cloudflared tunnel --url http://localhost:4200 \
    > "$LOGS/tunnel.log" 2>&1 < /dev/null & disown
  for i in {1..45}; do
    URL=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOGS/tunnel.log" 2>/dev/null | head -1)
    [[ -n "${URL:-}" ]] && break
    sleep 1
  done
  if [[ -n "${URL:-}" ]]; then
    echo "$URL" > "$LOGS/public-url.txt"
    echo "     PUBLIC URL: $URL"
  else
    echo "     tunnel did not start - see $LOGS/tunnel.log"
  fi
else
  say "5/5  Tunnel skipped (use --tunnel for a public URL)"
fi

echo
echo "=========================================================="
echo "  READY"
echo "  Local:   http://localhost:4200"
[[ -n "${URL:-}" ]] && echo "  Public:  $URL"
echo
echo "  Sign in: owner / owner-demo      (full access, can refresh)"
echo "           reviewer / reviewer-demo (can review meaning)"
echo "           reader / reader-demo     (limited scope - demos the boundary)"
echo "=========================================================="

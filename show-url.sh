#!/usr/bin/env bash
# Prints the current public demo URL, if a tunnel is running.
cd "$(dirname "$0")"
URL=$(grep -oE "https://[a-zA-Z0-9.-]+\.(trycloudflare\.com|ngrok[a-z.-]*)" .logs/tunnel.log 2>/dev/null | head -1)
if [[ -n "$URL" ]] && pgrep -f "cloudflared tunnel|ngrok http" >/dev/null; then
  echo "$URL"
else
  echo "No tunnel running. Start one with: ./start-demo.sh --tunnel"
fi

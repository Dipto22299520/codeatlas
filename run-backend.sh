#!/usr/bin/env bash
# Starts the CodeAtlas backend with environment from .env.
set -euo pipefail
cd "$(dirname "$0")"
if [[ -f .env ]]; then
  set -a; source ./.env; set +a
fi
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
exec ./backend/mvnw -f backend/pom.xml spring-boot:run

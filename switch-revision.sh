#!/usr/bin/env bash
# Points registered assets at a fixture revision (A or B) and shows the result.
set -euo pipefail
REV="${1:-A}"
[[ "$REV" == "A" || "$REV" == "B" ]] || { echo "usage: $0 [A|B]"; exit 1; }
cd "$(dirname "$0")"
PGPASSWORD=codeatlas psql -h localhost -p 55432 -U codeatlas -d codeatlas -q -c \
  "UPDATE asset SET source_locator = regexp_replace(source_locator, '^revisions/[AB]/', 'revisions/$REV/') WHERE language = 'java';"
echo "Assets now pointing at revision $REV:"
PGPASSWORD=codeatlas psql -h localhost -p 55432 -U codeatlas -d codeatlas -t -c \
  "SELECT id || ' -> ' || source_locator FROM asset ORDER BY id;"

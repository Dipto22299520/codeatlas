#!/usr/bin/env bash
# Points the Flowable demo asset at revision v1 or v2.
#   v1 = original code
#   v2 = a developer renamed deleteProcessInstance -> removeProcessInstance
set -e
V="${1:-v1}"
[[ "$V" == "v1" || "$V" == "v2" ]] || { echo "usage: $0 [v1|v2]"; exit 1; }
cd "$(dirname "$0")"
PGPASSWORD=codeatlas psql -h localhost -p 55432 -U codeatlas -d codeatlas -q -c \
  "UPDATE asset SET source_locator = 'flowable-demo/rest-$V' WHERE id = 'flowable-rest';"
echo "flowable-rest now points at rest-$V"

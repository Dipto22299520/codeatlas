#!/usr/bin/env bash
# Runs the evaluation corpus against a running backend and prints measured results.
set -uo pipefail
BASE="${BASE:-http://localhost:8090}"
AUTH="${AUTH:-reviewer:reviewer-demo}"
python3 "$(dirname "$0")/evaluate.py" "$BASE" "$AUTH"

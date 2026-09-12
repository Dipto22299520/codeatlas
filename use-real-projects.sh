#!/usr/bin/env bash
# Switches to the real Java projects (Flowable, BPA, gRPC, Quarkus).
set -e
cd "$(dirname "$0")"
PGPASSWORD=codeatlas psql -h localhost -p 55432 -U codeatlas -d codeatlas -q <<'SQL'
UPDATE asset SET scope_status='excluded'
  WHERE id IN ('purchase-portal','approval-service','payment-service','supplier-ui');
-- Everything that is not a demo fixture asset comes into scope.
UPDATE asset SET scope_status='in_scope'
  WHERE id NOT IN ('purchase-portal','approval-service','payment-service','supplier-ui');
-- Demo anchors point at com.example.* symbols absent from real code.
UPDATE business_meaning_version SET status='superseded' WHERE status='reviewed';
SQL
sed -i 's|^CODEATLAS_SOURCE_ROOT=.*|CODEATLAS_SOURCE_ROOT="/home/diptosumit/Downloads/java projects"|' .env
echo "Real projects active. Run ./restart-backend.sh then refresh."

#!/usr/bin/env bash
# Restores the synthetic demo estate (fixture assets in scope, demo source root).
set -e
cd "$(dirname "$0")"
PGPASSWORD=codeatlas psql -h localhost -p 55432 -U codeatlas -d codeatlas -q <<'SQL'
UPDATE asset SET scope_status='in_scope'
  WHERE id IN ('purchase-portal','approval-service','payment-service','supplier-ui');
-- Everything that is not a demo fixture asset goes out of scope.
UPDATE asset SET scope_status='excluded'
  WHERE id NOT IN ('purchase-portal','approval-service','payment-service','supplier-ui');
-- Restore the demo reviewed meanings (the newest version of each).
UPDATE business_meaning_version v SET status='reviewed'
  WHERE v.meaning_id IN ('bm-threshold-rule','bm-eligibility','bm-capability')
    AND v.version = (SELECT max(version) FROM business_meaning_version x
                     WHERE x.meaning_id = v.meaning_id);
UPDATE business_meaning m SET current_version =
  (SELECT max(version) FROM business_meaning_version x WHERE x.meaning_id = m.id)
  WHERE m.id IN ('bm-threshold-rule','bm-eligibility','bm-capability');
SQL
sed -i 's|^CODEATLAS_SOURCE_ROOT=.*|CODEATLAS_SOURCE_ROOT=../demo-estate|' .env
echo "Demo estate restored. Run ./restart-backend.sh then refresh."

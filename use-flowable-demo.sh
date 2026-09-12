#!/usr/bin/env bash
# Switches the estate to the Flowable drift demo.
set -e
cd "$(dirname "$0")"
sed -i 's|^CODEATLAS_SOURCE_ROOT=.*|CODEATLAS_SOURCE_ROOT="/home/diptosumit/Downloads/java projects"|' .env
PGPASSWORD=codeatlas psql -h localhost -p 55432 -U codeatlas -d codeatlas -q <<'SQL'
UPDATE asset SET scope_status='excluded'
  WHERE id IN ('purchase-portal','approval-service','payment-service','supplier-ui');
INSERT INTO asset (id, business_name, technical_type, role, owner, source_locator,
                   sensitivity, scope_status, language)
VALUES ('flowable-rest','Flowable Process Engine REST API','spring-boot-module',
        'Process engine REST surface','Platform Engineering',
        'flowable-demo/rest-v1','internal','in_scope','java')
ON CONFLICT (id) DO UPDATE SET scope_status='in_scope', source_locator='flowable-demo/rest-v1';
INSERT INTO user_asset_scope (username, asset_id)
VALUES ('reader','flowable-rest'),('reviewer','flowable-rest')
ON CONFLICT DO NOTHING;
-- Demo fixture meanings anchor to com.example.* symbols absent from Flowable.
UPDATE business_meaning_version SET status='superseded'
  WHERE status='reviewed' AND meaning_id IN ('bm-threshold-rule','bm-eligibility','bm-capability');
SQL
echo "Flowable demo estate active. Restart the backend, then refresh as owner."

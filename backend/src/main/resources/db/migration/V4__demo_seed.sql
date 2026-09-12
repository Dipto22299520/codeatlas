-- Demo fixture seed. All content here is explicitly synthetic: the reviewed
-- meaning is labelled as demo-reviewed, not a real organizational approval
-- (README section 9).

INSERT INTO platform_settings (id, platform_owner, refresh_cadence)
VALUES (1, 'Mazhar Ibna Zahur (demo platform owner)', 'on-demand')
ON CONFLICT (id) DO NOTHING;

-- Passwords are bcrypt hashes of the usernames suffixed with '-demo'.
-- owner/owner-demo, reviewer/reviewer-demo, reader/reader-demo, assistant/assistant-demo
INSERT INTO platform_user (username, password_hash, role, display_name) VALUES
  ('owner',     '$2a$10$7PMR5FRtpePLSNAc2PQG1OY/Ifc5c9Hdf3MkQlvros94hcxL8JH4S', 'OWNER',     'Demo Platform Owner'),
  ('reviewer',  '$2a$10$z75zmsgZOtzub2RaNpvqO.p15/ZXdN2fBSMHx6I8vpUrq.oZBU08C', 'REVIEWER',  'Demo Reviewer'),
  ('reader',    '$2a$10$UAqaBY2saoRG2ZLomqkkPe.8SqkxmMZS1j.4UI24bJcjLgwmrO316', 'READER',    'Demo Reader'),
  ('assistant', '$2a$10$VzN1UzpRBkx/7Kxz2H.Ql.I/Ceia.Sv8g7jprVCjY5ZVB7yOe3NJW', 'ASSISTANT', 'Demo Assistant Client');

-- Registered estate. Source locators are relative to the configured read-only root.
INSERT INTO asset (id, business_name, technical_type, role, owner, source_locator,
                   sensitivity, scope_status, language) VALUES
  ('purchase-portal',  'Purchase Portal',   'spring-boot-service', 'Request intake',
   'Procurement Systems Team', 'revisions/A/purchase-portal',  'internal', 'in_scope', 'java'),
  ('approval-service', 'Approval Service',  'spring-boot-service', 'Approval decisioning',
   'Procurement Systems Team', 'revisions/A/approval-service', 'internal', 'in_scope', 'java'),
  ('payment-service',  'Payment Service',   'spring-boot-service', 'Payment eligibility',
   'Finance Platform Team',    'revisions/A/payment-service',  'internal', 'in_scope', 'java');

-- A registered asset with no analyzer: an honest, visible coverage gap (BR-13).
INSERT INTO asset (id, business_name, technical_type, role, owner, source_locator,
                   sensitivity, scope_status, language) VALUES
  ('supplier-ui', 'Supplier Web UI', 'angular-application', 'Supplier self-service',
   'Procurement Systems Team', 'revisions/A/purchase-portal', 'internal', 'in_scope', 'typescript');

-- Reader sees only the two procurement services; payment-service is out of scope.
-- This makes the authorization boundary demonstrable (BR-71).
INSERT INTO user_asset_scope (username, asset_id) VALUES
  ('reader', 'purchase-portal'),
  ('reader', 'approval-service'),
  ('reviewer', 'purchase-portal'),
  ('reviewer', 'approval-service'),
  ('reviewer', 'payment-service'),
  ('assistant', 'purchase-portal'),
  ('assistant', 'approval-service'),
  ('assistant', 'payment-service');

-- Explicit reviewed configuration allowlist. Nothing else is ever snapshotted.
INSERT INTO reference_allowlist (asset_id, config_key, value_type, approved_by) VALUES
  ('approval-service', 'approval.threshold.amount',   'number',  'Demo Platform Owner'),
  ('approval-service', 'approval.auto-approve.enabled','boolean','Demo Platform Owner'),
  ('payment-service',  'payment.daily-limit.amount',  'number',  'Demo Platform Owner');

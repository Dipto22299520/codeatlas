-- Demo-reviewed business meaning and the purchase approval process.
-- 'Demo Reviewer' is a fixture identity, not a real organizational approval.

INSERT INTO business_meaning (id, meaning_type, business_name, current_version) VALUES
  ('bm-threshold-rule', 'rule',       'Purchase approval threshold', 1),
  ('bm-eligibility',    'rule',       'Supplier spending eligibility', 1),
  ('bm-capability',     'capability', 'Purchase approval', 1);

INSERT INTO business_meaning_version
  (id, meaning_id, version, statement, owner, status, author, reason, reviewer, reviewed_at, source_origin) VALUES
  ('bmv-threshold-1', 'bm-threshold-rule', 1,
   'A purchase request above the configured approval threshold cannot be approved automatically. It is escalated for manual review, and the escalation is recorded against the request.',
   'Procurement Policy', 'reviewed', 'Demo Author',
   'Documents the spending control applied to every purchase request.',
   'Demo Reviewer', now(), 'human'),
  ('bmv-eligibility-1', 'bm-eligibility', 1,
   'Before a purchase is approved, the payment service confirms the requester is not blocked and remains within the daily spending limit.',
   'Finance Policy', 'reviewed', 'Demo Author',
   'Explains the cross-team eligibility control.',
   'Demo Reviewer', now(), 'human'),
  ('bmv-capability-1', 'bm-capability', 1,
   'Staff submit purchase requests through the portal. Requests are validated, checked against the spending threshold, confirmed for payment eligibility, and recorded with an outcome.',
   'Procurement Systems Team', 'reviewed', 'Demo Author',
   'Top-level description of the purchase approval capability.',
   'Demo Reviewer', now(), 'human');

-- Anchors bind each statement to specific software symbols (BR-16).
-- The threshold anchor points at ApprovalService.approve, which revision B
-- renames: that is what makes the broken-anchor demonstration real.
INSERT INTO meaning_anchor
  (id, meaning_version_id, asset_id, path, symbol_key, confidence_basis) VALUES
  ('anc-threshold-1', 'bmv-threshold-1', 'approval-service',
   'src/main/java/com/example/approval/ApprovalService.java',
   'com.example.approval.ApprovalService.approve',
   'The method compares the request amount against the injected threshold property and escalates when it is exceeded.'),
  ('anc-threshold-2', 'bmv-threshold-1', 'approval-service',
   'src/main/resources/application.yml',
   'approval.threshold.amount',
   'The configuration key supplying the threshold value.'),
  ('anc-eligibility-1', 'bmv-eligibility-1', 'payment-service',
   'src/main/java/com/example/payment/EligibilityService.java',
   'com.example.payment.EligibilityService.isEligible',
   'The method applies the block check and the daily limit comparison.'),
  ('anc-capability-1', 'bmv-capability-1', 'purchase-portal',
   'src/main/java/com/example/portal/PurchaseRequestService.java',
   'com.example.portal.PurchaseRequestService.submit',
   'The entry point that validates a submission and hands it to approval.');

-- The purchase approval process: ordered stages across three applications.
INSERT INTO business_process (id, business_name, description, owner, order_basis) VALUES
  ('proc-purchase-approval', 'Purchase approval',
   'How a purchase request travels from portal submission to a recorded approval outcome.',
   'Procurement Systems Team', 'curated');

INSERT INTO process_stage
  (id, process_id, stage_order, name, description, asset_id, stage_kind, branch_condition, symbol_key, path) VALUES
  ('ps-1', 'proc-purchase-approval', 1, 'Submit purchase request',
   'A staff member submits a request through the portal.', 'purchase-portal', 'entry', NULL,
   'com.example.portal.PurchaseRequestController.submit',
   'src/main/java/com/example/portal/PurchaseRequestController.java'),
  ('ps-2', 'proc-purchase-approval', 2, 'Require a justification',
   'The portal rejects a submission with no justification.', 'purchase-portal', 'check',
   'justification is blank', 'com.example.portal.PurchaseRequestService.submit',
   'src/main/java/com/example/portal/PurchaseRequestService.java'),
  ('ps-3', 'proc-purchase-approval', 3, 'Record the submission',
   'The portal stores the request before handing it over.', 'purchase-portal', 'effect', NULL,
   'com.example.portal.SubmissionRepository.saveSubmission',
   'src/main/java/com/example/portal/SubmissionRepository.java'),
  ('ps-4', 'proc-purchase-approval', 4, 'Hand over to approval service',
   'The portal calls the approval service over HTTP.', 'purchase-portal', 'handover', NULL,
   'com.example.portal.PurchaseRequestService.submit',
   'src/main/java/com/example/portal/PurchaseRequestService.java'),
  ('ps-5', 'proc-purchase-approval', 5, 'Validate cost centre and amount',
   'The approval service rejects a missing cost centre or a non-positive amount.',
   'approval-service', 'check', 'cost centre missing or amount not positive',
   'com.example.approval.ApprovalService.approve',
   'src/main/java/com/example/approval/ApprovalService.java'),
  ('ps-6', 'proc-purchase-approval', 6, 'Apply the approval threshold',
   'Requests above the configured threshold are escalated for manual review.',
   'approval-service', 'check', 'amount exceeds approval.threshold.amount',
   'com.example.approval.ApprovalService.approve',
   'src/main/java/com/example/approval/ApprovalService.java'),
  ('ps-7', 'proc-purchase-approval', 7, 'Confirm payment eligibility',
   'The approval service asks the payment service whether the requester may spend.',
   'approval-service', 'handover', NULL,
   'com.example.approval.EligibilityClient.checkEligibility',
   'src/main/java/com/example/approval/EligibilityClient.java'),
  ('ps-8', 'proc-purchase-approval', 8, 'Check block list and daily limit',
   'The payment service rejects blocked requesters and spending beyond the daily limit.',
   'payment-service', 'check', 'requester blocked or daily limit exceeded',
   'com.example.payment.EligibilityService.isEligible',
   'src/main/java/com/example/payment/EligibilityService.java'),
  ('ps-9', 'proc-purchase-approval', 9, 'Record the decision',
   'The outcome is written to the approval datastore.', 'approval-service', 'effect', NULL,
   'com.example.approval.ApprovalRepository.saveDecision',
   'src/main/java/com/example/approval/ApprovalRepository.java'),
  ('ps-10', 'proc-purchase-approval', 10, 'Escalation notification',
   'Escalations are announced through a handler chosen at runtime. The concrete target cannot be determined by static analysis and is reported as a coverage gap.',
   'approval-service', 'failure', 'escalation handler unresolved',
   'com.example.approval.AuditNotifier.notifyEscalation',
   'src/main/java/com/example/approval/AuditNotifier.java');

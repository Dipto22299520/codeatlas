package com.example.approval;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Applies the ordered purchase approval checks.
 *
 * Check order is significant: budget validity is established before the
 * spending threshold is applied, and eligibility is confirmed last because
 * it requires a remote call.
 */
@Service
public class ApprovalService {

    private final ApprovalRepository approvalRepository;
    private final EligibilityClient eligibilityClient;
    private final AuditNotifier auditNotifier;

    @Value("${approval.threshold.amount}")
    private BigDecimal thresholdAmount;

    @Value("${approval.auto-approve.enabled}")
    private boolean autoApproveEnabled;

    public ApprovalService(ApprovalRepository approvalRepository,
                           EligibilityClient eligibilityClient,
                           AuditNotifier auditNotifier) {
        this.approvalRepository = approvalRepository;
        this.eligibilityClient = eligibilityClient;
        this.auditNotifier = auditNotifier;
    }

    public ApprovalDecision decideApproval(PurchaseRequest request) {
        // Check 1 - the request must carry a cost centre.
        if (request.getCostCentre() == null || request.getCostCentre().isBlank()) {
            return reject(request, "MISSING_COST_CENTRE");
        }

        // Check 2 - the amount must be positive.
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            return reject(request, "INVALID_AMOUNT");
        }

        // Check 3 - only euro purchases are handled by this service.
        if (request.getCurrency() != null && !"EUR".equals(request.getCurrency())) {
            return reject(request, "UNSUPPORTED_CURRENCY");
        }

        // Check 4 - the configured spending threshold decides whether the
        // request can be auto-approved or must be escalated for review.
        if (request.getAmount().compareTo(thresholdAmount) > 0) {
            approvalRepository.saveDecision(request.getRequestId(), "ESCALATED");
            auditNotifier.notifyEscalation(request.getRequestId());
            return new ApprovalDecision(request.getRequestId(), "ESCALATED",
                    "Amount exceeds approval threshold and requires manual review.");
        }

        // Check 5 - remote eligibility confirmation in the payment service.
        boolean eligible = eligibilityClient.checkEligibility(
                request.getRequesterId(), request.getAmount());
        if (!eligible) {
            return reject(request, "PAYMENT_ELIGIBILITY_DENIED");
        }

        if (!autoApproveEnabled) {
            approvalRepository.saveDecision(request.getRequestId(), "PENDING");
            return new ApprovalDecision(request.getRequestId(), "PENDING",
                    "Automatic approval is disabled by configuration.");
        }

        approvalRepository.saveDecision(request.getRequestId(), "APPROVED");
        return new ApprovalDecision(request.getRequestId(), "APPROVED",
                "Within threshold and eligible for payment.");
    }

    private ApprovalDecision reject(PurchaseRequest request, String code) {
        approvalRepository.saveDecision(request.getRequestId(), "REJECTED");
        return new ApprovalDecision(request.getRequestId(), "REJECTED", code);
    }
}

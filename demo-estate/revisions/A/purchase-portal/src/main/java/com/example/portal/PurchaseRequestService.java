package com.example.portal;

import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Validates a submitted form and hands it over to the approval service.
 */
@Service
public class PurchaseRequestService {

    private final RestTemplate restTemplate;
    private final SubmissionRepository submissionRepository;

    @Value("${approval.service.base-url}")
    private String approvalBaseUrl;

    public PurchaseRequestService(RestTemplate restTemplate,
                                  SubmissionRepository submissionRepository) {
        this.restTemplate = restTemplate;
        this.submissionRepository = submissionRepository;
    }

    public SubmissionResult submit(SubmissionForm form) {
        // Portal-side check - a justification is required before handover.
        if (form.getJustification() == null || form.getJustification().isBlank()) {
            return new SubmissionResult(null, "REJECTED", "JUSTIFICATION_REQUIRED");
        }

        String requestId = UUID.randomUUID().toString();
        submissionRepository.saveSubmission(requestId, form.getRequesterId(), form.getAmount());

        Map<String, Object> payload = Map.of(
                "requestId", requestId,
                "requesterId", form.getRequesterId(),
                "costCentre", form.getCostCentre(),
                "amount", form.getAmount(),
                "currency", "EUR");

        Map<?, ?> decision = restTemplate.postForObject(
                approvalBaseUrl + "/api/approvals/decide", payload, Map.class);

        String outcome = decision == null ? "UNKNOWN" : String.valueOf(decision.get("outcome"));
        String reason = decision == null ? null : String.valueOf(decision.get("reason"));
        return new SubmissionResult(requestId, outcome, reason);
    }

    public SubmissionResult status(String requestId) {
        String outcome = submissionRepository.findStatus(requestId);
        return new SubmissionResult(requestId, outcome, null);
    }
}

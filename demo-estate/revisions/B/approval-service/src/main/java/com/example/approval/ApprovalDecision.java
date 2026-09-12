package com.example.approval;

public class ApprovalDecision {
    private final String requestId;
    private final String outcome;
    private final String reason;

    public ApprovalDecision(String requestId, String outcome, String reason) {
        this.requestId = requestId;
        this.outcome = outcome;
        this.reason = reason;
    }

    public String getRequestId() { return requestId; }
    public String getOutcome() { return outcome; }
    public String getReason() { return reason; }
}

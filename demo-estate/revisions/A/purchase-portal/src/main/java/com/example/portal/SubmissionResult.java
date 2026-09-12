package com.example.portal;

public class SubmissionResult {
    private final String requestId;
    private final String outcome;
    private final String message;

    public SubmissionResult(String requestId, String outcome, String message) {
        this.requestId = requestId;
        this.outcome = outcome;
        this.message = message;
    }

    public String getRequestId() { return requestId; }
    public String getOutcome() { return outcome; }
    public String getMessage() { return message; }
}

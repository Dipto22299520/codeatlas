package com.example.approval;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Calls the payment service to confirm that a requester may spend.
 * This is the cross-application handover in the purchase approval process.
 */
@Component
public class EligibilityClient {

    private final RestTemplate restTemplate;

    @Value("${payment.service.base-url}")
    private String paymentBaseUrl;

    public EligibilityClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean checkEligibility(String requesterId, BigDecimal amount) {
        Map<String, Object> body = Map.of(
                "requesterId", requesterId,
                "amount", amount);
        Boolean eligible = restTemplate.postForObject(
                paymentBaseUrl + "/api/payments/eligibility", body, Boolean.class);
        return Boolean.TRUE.equals(eligible);
    }
}

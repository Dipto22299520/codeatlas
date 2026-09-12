package com.example.payment;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives eligibility questions from the approval service.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentEligibilityController {

    private final EligibilityService eligibilityService;

    public PaymentEligibilityController(EligibilityService eligibilityService) {
        this.eligibilityService = eligibilityService;
    }

    @PostMapping("/eligibility")
    public boolean eligibility(@RequestBody EligibilityQuery query) {
        return eligibilityService.isEligible(query);
    }
}

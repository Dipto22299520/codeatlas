package com.example.portal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where staff submit purchase requests. This is the entry point of the
 * purchase approval process.
 */
@RestController
@RequestMapping("/api/purchase-requests")
public class PurchaseRequestController {

    private final PurchaseRequestService purchaseRequestService;

    public PurchaseRequestController(PurchaseRequestService purchaseRequestService) {
        this.purchaseRequestService = purchaseRequestService;
    }

    @PostMapping
    public SubmissionResult submit(@RequestBody SubmissionForm form) {
        return purchaseRequestService.submit(form);
    }

    @GetMapping("/{requestId}")
    public SubmissionResult status(@PathVariable String requestId) {
        return purchaseRequestService.status(requestId);
    }
}

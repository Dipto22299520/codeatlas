package com.example.payment;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Decides whether a requester may spend against a supplier budget.
 */
@Service
public class EligibilityService {

    private final SupplierLedgerRepository supplierLedgerRepository;

    @Value("${payment.daily-limit.amount}")
    private BigDecimal dailyLimit;

    public EligibilityService(SupplierLedgerRepository supplierLedgerRepository) {
        this.supplierLedgerRepository = supplierLedgerRepository;
    }

    public boolean isEligible(EligibilityQuery query) {
        if (query.getRequesterId() == null || query.getRequesterId().isBlank()) {
            return false;
        }
        if (supplierLedgerRepository.isBlocked(query.getRequesterId())) {
            return false;
        }
        BigDecimal spentToday = supplierLedgerRepository.spentToday(query.getRequesterId());
        BigDecimal projected = spentToday.add(query.getAmount());
        return projected.compareTo(dailyLimit) <= 0;
    }
}

package com.example.payment;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the supplier spending ledger.
 */
@Repository
public class SupplierLedgerRepository {

    private final JdbcTemplate jdbcTemplate;

    public SupplierLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isBlocked(String requesterId) {
        Integer blocked = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM supplier_block WHERE requester_id = ?",
                Integer.class, requesterId);
        return blocked != null && blocked > 0;
    }

    public BigDecimal spentToday(String requesterId) {
        BigDecimal spent = jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(amount), 0) FROM supplier_ledger WHERE requester_id = ? AND spent_on = current_date",
                BigDecimal.class, requesterId);
        return spent == null ? BigDecimal.ZERO : spent;
    }
}

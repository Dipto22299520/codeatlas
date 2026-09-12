package com.example.portal;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Stores submitted purchase requests in the portal datastore.
 */
@Repository
public class SubmissionRepository {

    private final JdbcTemplate jdbcTemplate;

    public SubmissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveSubmission(String requestId, String requesterId, BigDecimal amount) {
        jdbcTemplate.update(
                "INSERT INTO purchase_submission (request_id, requester_id, amount, submitted_at) VALUES (?, ?, ?, now())",
                requestId, requesterId, amount);
    }

    public String findStatus(String requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM purchase_submission WHERE request_id = ?",
                String.class, requestId);
    }
}

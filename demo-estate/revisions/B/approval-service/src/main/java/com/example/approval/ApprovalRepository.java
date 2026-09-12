package com.example.approval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Stores approval outcomes in the approval datastore.
 */
@Repository
public class ApprovalRepository {

    private final JdbcTemplate jdbcTemplate;

    public ApprovalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveDecision(String requestId, String outcome) {
        jdbcTemplate.update(
                "INSERT INTO approval_decision (request_id, outcome, decided_at) VALUES (?, ?, now())",
                requestId, outcome);
    }

    public String findOutcome(String requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT outcome FROM approval_decision WHERE request_id = ?",
                String.class, requestId);
    }
}

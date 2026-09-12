package com.codeatlas.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Records caller, time, action, target, and result (BR-71).
 * Raw prompts and source text are deliberately not stored (README section 11).
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String client, String action, String target,
                       String result, String requestId) {
        jdbc.update("INSERT INTO audit_event (actor, client, action, target, result, request_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)", actor, client, action, target, result, requestId);
    }
}

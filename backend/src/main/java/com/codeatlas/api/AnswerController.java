package com.codeatlas.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codeatlas.agents.AnswerOrchestrator;
import com.codeatlas.answers.AnswerModels;
import com.codeatlas.security.Principal;
import com.codeatlas.security.ScopeService;

/** The natural-language question endpoint backing the Workspace. */
@RestController
@RequestMapping("/api/answers")
public class AnswerController {

    private final AnswerOrchestrator orchestrator;
    private final ScopeService scopeService;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public AnswerController(AnswerOrchestrator orchestrator, ScopeService scopeService,
                            AuditService audit, JdbcTemplate jdbc) {
        this.orchestrator = orchestrator;
        this.scopeService = scopeService;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public record AskRequest(String question) {
    }

    public record AskResponse(AnswerModels.Answer answer,
                              List<AnswerOrchestrator.Progress> progress) {
    }

    @PostMapping
    public AskResponse ask(@RequestBody AskRequest request) {
        Principal principal = scopeService.currentPrincipal();
        List<AnswerOrchestrator.Progress> progress = new ArrayList<>();
        long started = System.currentTimeMillis();

        AnswerModels.Answer answer = orchestrator.answer(request.question(), principal, progress);
        long latency = System.currentTimeMillis() - started;

        // Record the run for traceability (README section 5, AnswerRun).
        jdbc.update("INSERT INTO answer_run (id, caller, service_id, question, authorized_scope, "
                + "generation_id, status, evidence_ids, model_name, tokens, cost, latency_ms) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                answer.requestId(), principal.username(),
                answer.data() == null ? "unknown" : String.valueOf(answer.data().get("service")),
                request.question(),
                principal.authorizedAssets().toArray(new String[0]),
                answer.freshness() == null ? null : answer.freshness().generation(),
                answer.status().wire(),
                answer.evidence().stream().map(AnswerModels.Evidence::id).toArray(String[]::new),
                answer.usage() == null ? null : answer.usage().model(),
                answer.usage() == null ? null : answer.usage().tokens(),
                answer.usage() == null ? null : answer.usage().cost(),
                (int) latency);

        audit.record(principal.username(), "rest", "ask", request.question(),
                answer.status().wire() + " in " + latency + "ms", answer.requestId());
        return new AskResponse(answer, progress);
    }

    /** Recent answer runs, for the trust view. */
    @PostMapping("/runs")
    public List<Map<String, Object>> runs() {
        Principal principal = scopeService.currentPrincipal();
        return jdbc.queryForList(
                "SELECT id, service_id, question, status, model_name, tokens, cost, latency_ms, "
                + "created_at FROM answer_run WHERE caller = ? ORDER BY created_at DESC LIMIT 25",
                principal.username());
    }
}

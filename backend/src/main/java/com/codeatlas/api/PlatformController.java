package com.codeatlas.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codeatlas.answers.AnswerModels;
import com.codeatlas.answers.AnswerServiceHandlers;
import com.codeatlas.answers.EvidenceService;
import com.codeatlas.assets.AssetRegistry;
import com.codeatlas.config.CodeAtlasProperties;
import com.codeatlas.refresh.RefreshService;
import com.codeatlas.security.Principal;
import com.codeatlas.security.ScopeService;

import org.springframework.jdbc.core.JdbcTemplate;

/** Registry, refresh, status, and source inspection routes. */
@RestController
@RequestMapping("/api")
public class PlatformController {

    private final AssetRegistry registry;
    private final RefreshService refreshService;
    private final AnswerServiceHandlers handlers;
    private final EvidenceService evidenceService;
    private final ScopeService scopeService;
    private final AuditService audit;
    private final CodeAtlasProperties properties;
    private final JdbcTemplate jdbc;

    public PlatformController(AssetRegistry registry, RefreshService refreshService,
                              AnswerServiceHandlers handlers, EvidenceService evidenceService,
                              ScopeService scopeService, AuditService audit,
                              CodeAtlasProperties properties, JdbcTemplate jdbc) {
        this.registry = registry;
        this.refreshService = refreshService;
        this.handlers = handlers;
        this.evidenceService = evidenceService;
        this.scopeService = scopeService;
        this.audit = audit;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    /** The estate view: only assets the caller may read. */
    @GetMapping("/assets")
    public List<Map<String, Object>> assets() {
        Principal principal = scopeService.currentPrincipal();
        return jdbc.queryForList(
                "SELECT a.*, "
                + " (SELECT count(*) FROM asset_exclusion e WHERE e.asset_id = a.id) AS exclusion_count, "
                + " (SELECT max(r.indexed_at) FROM source_revision r WHERE r.asset_id = a.id) AS last_indexed "
                + "FROM asset a WHERE a.id = ANY(?) ORDER BY a.id",
                (Object) principal.authorizedAssets().toArray(new String[0]));
    }

    @PostMapping("/assets")
    public ResponseEntity<?> register(@RequestBody AssetRegistry.Asset asset) {
        Principal principal = scopeService.currentPrincipal();
        try {
            registry.register(asset);
            audit.record(principal.username(), "rest", "register_asset", asset.id(), "ok", null);
            return ResponseEntity.ok(Map.of("id", asset.id(), "status", "registered"));
        } catch (AssetRegistry.SourcePathRejected e) {
            audit.record(principal.username(), "rest", "register_asset", asset.id(),
                    "rejected: " + e.getMessage(), null);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Runs a refresh. Owner-only, enforced by the security filter chain. */
    @PostMapping("/refresh")
    public RefreshService.RefreshResult refresh(@RequestBody(required = false) Map<String, Object> body) {
        Principal principal = scopeService.currentPrincipal();
        String scope = body == null ? "full" : String.valueOf(body.getOrDefault("scope", "full"));
        String assetId = body == null ? null
                : (body.get("assetId") == null ? null : String.valueOf(body.get("assetId")));
        RefreshService.RefreshResult result = refreshService.refresh(scope, assetId, principal.username());
        audit.record(principal.username(), "rest", "refresh", scope + ":" + assetId,
                result.state(), result.jobId());
        return result;
    }

    @GetMapping("/jobs/{id}")
    public Map<String, Object> job(@PathVariable String id) {
        Map<String, Object> job = jdbc.queryForList(
                "SELECT * FROM refresh_job WHERE id = ?", id).stream().findFirst()
                .orElse(Map.of("error", "not found"));
        List<Map<String, Object>> stages = jdbc.queryForList(
                "SELECT * FROM refresh_job_stage WHERE job_id = ? ORDER BY stage_order", id);
        return Map.of("job", job, "stages", stages);
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs() {
        return jdbc.queryForList(
                "SELECT * FROM refresh_job ORDER BY started_at DESC LIMIT 20");
    }

    /** Platform status, including profile and inference posture. */
    @GetMapping("/status")
    public AnswerModels.Answer status() {
        Principal principal = scopeService.currentPrincipal();
        return handlers.invoke("status", Map.of(), principal);
    }

    /** Who am I: drives role-dependent UI without a second auth mechanism. */
    @GetMapping("/me")
    public Map<String, Object> me() {
        Principal principal = scopeService.currentPrincipal();
        return Map.of(
                "username", principal.username(),
                "role", principal.role(),
                "authorizedAssets", principal.authorizedAssets(),
                "profile", properties.getProfile(),
                "externalInference", !properties.isEnterprise()
                        && properties.getModel().isConfigured());
    }

    /** Exact source at a recorded location, scope-checked. */
    @GetMapping("/source/{locationId}")
    public ResponseEntity<?> source(@PathVariable String locationId) {
        Principal principal = scopeService.currentPrincipal();
        AnswerModels.Evidence evidence = evidenceService.loadOne(principal, locationId);
        if (evidence == null) {
            audit.record(principal.username(), "rest", "read_source", locationId, "denied", null);
            return ResponseEntity.status(403).body(Map.of(
                    "error", "That source location is not available in your authorized scope."));
        }
        audit.record(principal.username(), "rest", "read_source", locationId, "ok", null);
        return ResponseEntity.ok(evidence);
    }

    @GetMapping("/coverage")
    public List<Map<String, Object>> coverage(@RequestParam(required = false) String generationId) {
        Principal principal = scopeService.currentPrincipal();
        String generation = generationId != null ? generationId
                : jdbc.queryForList("SELECT id FROM knowledge_generation WHERE state = 'active' "
                        + "ORDER BY published_at DESC LIMIT 1", String.class)
                    .stream().findFirst().orElse(null);
        if (generation == null) {
            return List.of();
        }
        return jdbc.queryForList(
                "SELECT * FROM coverage_finding WHERE generation_id = ? AND asset_id = ANY(?) "
                + "ORDER BY asset_id, finding_type, path",
                generation, principal.authorizedAssets().toArray(new String[0]));
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> auditEvents() {
        Principal principal = scopeService.currentPrincipal();
        if (!principal.isOwner()) {
            return List.of();
        }
        return jdbc.queryForList(
                "SELECT * FROM audit_event ORDER BY occurred_at DESC LIMIT 100");
    }
}

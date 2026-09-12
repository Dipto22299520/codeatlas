package com.codeatlas.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codeatlas.security.Principal;
import com.codeatlas.security.ScopeService;

/**
 * Business meaning authoring and review (README section 9).
 *
 * Authoring writes a draft; review creates an immutable version with author,
 * reviewer, date, and rationale (BR-19). Nothing here writes to described
 * software - only to CodeAtlas-owned storage (section 2.2).
 */
@RestController
@RequestMapping("/api/meaning")
public class MeaningController {

    private final JdbcTemplate jdbc;
    private final ScopeService scopeService;
    private final AuditService audit;

    public MeaningController(JdbcTemplate jdbc, ScopeService scopeService, AuditService audit) {
        this.jdbc = jdbc;
        this.scopeService = scopeService;
        this.audit = audit;
    }

    /**
     * Meanings the caller is authorized to see.
     *
     * A statement is visible only when at least one of its anchors lies in the
     * caller's asset scope, so curated knowledge honours the same boundary as
     * retrieval (BR-71). Superseded versions are excluded: only the current
     * version and any open drafts are listed.
     */
    @GetMapping
    public List<Map<String, Object>> list() {
        Principal principal = scopeService.currentPrincipal();
        return jdbc.queryForList(
                "SELECT m.id, m.meaning_type, m.business_name, m.current_version, "
                + "       v.id AS version_id, v.statement, v.status, v.owner, v.author, "
                + "       v.authored_at, v.reason, v.reviewer, v.reviewed_at, v.source_origin, "
                + "       (SELECT count(*) FROM meaning_anchor a "
                + "          WHERE a.meaning_version_id = v.id) AS anchor_count, "
                + "       (SELECT count(*) FROM meaning_anchor a "
                + "          WHERE a.meaning_version_id = v.id AND a.resolution_status = 'broken') "
                + "          AS broken_anchor_count "
                + "FROM business_meaning m "
                + "JOIN business_meaning_version v ON v.meaning_id = m.id "
                + "WHERE v.status <> 'superseded' "
                + "  AND EXISTS (SELECT 1 FROM meaning_anchor a "
                + "              WHERE a.meaning_version_id = v.id AND a.asset_id = ANY(?)) "
                + "ORDER BY m.business_name, v.version DESC",
                (Object) principal.authorizedAssets().toArray(new String[0]));
    }

    @GetMapping("/{meaningId}/history")
    public List<Map<String, Object>> history(@PathVariable String meaningId) {
        return jdbc.queryForList(
                "SELECT v.*, (SELECT count(*) FROM meaning_anchor a "
                + "   WHERE a.meaning_version_id = v.id) AS anchor_count "
                + "FROM business_meaning_version v WHERE v.meaning_id = ? "
                + "ORDER BY v.version DESC", meaningId);
    }

    @GetMapping("/anchors")
    public List<Map<String, Object>> anchors() {
        Principal principal = scopeService.currentPrincipal();
        return jdbc.queryForList(
                "SELECT a.*, m.business_name, v.version, v.status "
                + "FROM meaning_anchor a "
                + "JOIN business_meaning_version v ON v.id = a.meaning_version_id "
                + "JOIN business_meaning m ON m.id = v.meaning_id "
                + "WHERE a.asset_id = ANY(?) AND v.status <> 'superseded' "
                + "  AND v.version = m.current_version "
                + "ORDER BY a.resolution_status, m.business_name",
                (Object) principal.authorizedAssets().toArray(new String[0]));
    }

    public record DraftRequest(String meaningId, String meaningType, String businessName,
                               String statement, String owner, String reason,
                               List<AnchorRequest> anchors) {
    }

    public record AnchorRequest(String assetId, String path, String symbolKey,
                                String confidenceBasis) {
    }

    /**
     * Creates a new draft version. Drafts never appear as reviewed facts (BR-21)
     * and are only published once a reviewer approves them.
     */
    @PostMapping
    @Transactional
    public ResponseEntity<?> draft(@RequestBody DraftRequest request) {
        Principal principal = scopeService.currentPrincipal();
        if (!principal.canReview() && !principal.isOwner()) {
            // Readers may not author meaning.
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Authoring business meaning requires the reviewer or owner role."));
        }
        if (request.statement() == null || request.statement().isBlank()
                || request.reason() == null || request.reason().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "A statement and a reason are both required."));
        }
        if (request.anchors() == null || request.anchors().isEmpty()) {
            // Every published statement needs specific software anchors (BR-16).
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "At least one source anchor is required."));
        }

        String meaningId = request.meaningId() != null ? request.meaningId()
                : "bm-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        jdbc.update("INSERT INTO business_meaning (id, meaning_type, business_name, current_version) "
                + "VALUES (?, ?, ?, 0) ON CONFLICT (id) DO NOTHING",
                meaningId, request.meaningType() == null ? "rule" : request.meaningType(),
                request.businessName());

        Integer nextVersion = jdbc.queryForObject(
                "SELECT coalesce(max(version), 0) + 1 FROM business_meaning_version WHERE meaning_id = ?",
                Integer.class, meaningId);
        String versionId = meaningId + "-v" + nextVersion;

        jdbc.update("INSERT INTO business_meaning_version (id, meaning_id, version, statement, "
                + "owner, status, author, reason, source_origin) "
                + "VALUES (?, ?, ?, ?, ?, 'draft', ?, ?, 'human')",
                versionId, meaningId, nextVersion, request.statement(),
                request.owner() == null ? principal.username() : request.owner(),
                principal.username(), request.reason());

        for (AnchorRequest anchor : request.anchors()) {
            jdbc.update("INSERT INTO meaning_anchor (id, meaning_version_id, asset_id, path, "
                    + "symbol_key, confidence_basis) VALUES (?, ?, ?, ?, ?, ?)",
                    "anc-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                    versionId, anchor.assetId(), anchor.path(), anchor.symbolKey(),
                    anchor.confidenceBasis() == null ? "Author supplied." : anchor.confidenceBasis());
        }

        audit.record(principal.username(), "rest", "draft_meaning", versionId, "created", null);
        return ResponseEntity.ok(Map.of(
                "meaningId", meaningId, "versionId", versionId,
                "version", nextVersion, "status", "draft"));
    }

    public record ReviewRequest(String versionId, String decision, String note) {
    }

    /**
     * Approves or rejects a draft. Approval promotes it to the current version
     * and records reviewer and date immutably.
     */
    @PostMapping("/{meaningId}/review")
    @Transactional
    public ResponseEntity<?> review(@PathVariable String meaningId,
                                    @RequestBody ReviewRequest request) {
        Principal principal = scopeService.currentPrincipal();
        String versionId = request.versionId();
        Map<String, Object> version = jdbc.queryForList(
                "SELECT * FROM business_meaning_version WHERE id = ? AND meaning_id = ?",
                versionId, meaningId).stream().findFirst().orElse(null);
        if (version == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"draft".equals(String.valueOf(version.get("status")))) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Only draft versions can be reviewed. "
                            + "Reviewed versions are immutable."));
        }

        boolean approve = "approve".equalsIgnoreCase(request.decision());
        if (!approve) {
            jdbc.update("UPDATE business_meaning_version SET status = 'rejected', reviewer = ?, "
                    + "reviewed_at = now(), review_note = ? WHERE id = ?",
                    principal.username(), request.note(), versionId);
            audit.record(principal.username(), "rest", "review_meaning", versionId, "rejected", null);
            return ResponseEntity.ok(Map.of("versionId", versionId, "status", "rejected"));
        }

        // Supersede the previous current version, then promote this one.
        jdbc.update("UPDATE business_meaning_version SET status = 'superseded' "
                + "WHERE meaning_id = ? AND status = 'reviewed'", meaningId);
        jdbc.update("UPDATE business_meaning_version SET status = 'reviewed', reviewer = ?, "
                + "reviewed_at = now(), review_note = ? WHERE id = ?",
                principal.username(), request.note(), versionId);
        jdbc.update("UPDATE business_meaning SET current_version = ? WHERE id = ?",
                version.get("version"), meaningId);

        audit.record(principal.username(), "rest", "review_meaning", versionId, "approved", null);
        return ResponseEntity.ok(Map.of(
                "versionId", versionId, "status", "reviewed",
                "reviewer", principal.username(),
                "note", "Run a refresh to validate anchors against current source."));
    }
}

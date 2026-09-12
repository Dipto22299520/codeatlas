package com.codeatlas.answers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.codeatlas.answers.AnswerModels.Answer;
import com.codeatlas.answers.AnswerModels.Claim;
import com.codeatlas.answers.AnswerModels.Status;
import com.codeatlas.config.CodeAtlasProperties;
import com.codeatlas.knowledge.KnowledgeStore;
import com.codeatlas.security.Principal;

/**
 * Deterministic handlers for the registered answer services.
 *
 * Every substantive claim carries evidence ids; nothing here consults a model.
 * Narrative composition happens later in the orchestrator and can only rephrase
 * what these handlers already established (README section 7).
 */
@Service
public class AnswerServiceHandlers {

    private final JdbcTemplate jdbc;
    private final KnowledgeStore store;
    private final EvidenceService evidenceService;
    private final ImpactTraversal traversal;
    private final CodeAtlasProperties properties;

    public AnswerServiceHandlers(JdbcTemplate jdbc, KnowledgeStore store,
                                 EvidenceService evidenceService, ImpactTraversal traversal,
                                 CodeAtlasProperties properties) {
        this.jdbc = jdbc;
        this.store = store;
        this.evidenceService = evidenceService;
        this.traversal = traversal;
        this.properties = properties;
    }

    /** Thrown when no generation has been published yet. */
    public static class NoKnowledgeException extends RuntimeException {
        public NoKnowledgeException(String message) {
            super(message);
        }
    }

    public Answer invoke(String serviceId, Map<String, Object> input, Principal principal) {
        String generationId = store.activeGenerationId();
        if (generationId == null) {
            return AnswerModels.Answer.builder()
                    .status(Status.UNKNOWN)
                    .summary("No knowledge generation has been published yet. "
                            + "Run a refresh to index the registered estate.")
                    .unknown("The knowledge store is empty.")
                    .nextEvidence("Run POST /api/refresh as a platform owner.")
                    .requestId(UUID.randomUUID().toString())
                    .build();
        }

        return switch (serviceId) {
            case "navigate" -> navigate(generationId, principal, string(input, "query"));
            case "search" -> search(generationId, principal, string(input, "query"),
                    string(input, "assetId"), string(input, "scope"));
            case "detail" -> detail(principal, string(input, "locationId"));
            case "impact" -> impact(generationId, principal, string(input, "target"),
                    string(input, "direction"), integer(input, "maxDepth", 4));
            case "configuration" -> configuration(principal, string(input, "key"),
                    string(input, "assetId"));
            case "flow" -> flow(generationId, principal, string(input, "processId"));
            case "checks" -> checks(generationId, principal, string(input, "processId"));
            case "effects" -> effects(generationId, principal, string(input, "processId"),
                    string(input, "symbol"));
            case "explain" -> explain(generationId, principal, string(input, "target"));
            case "status" -> status(generationId, principal);
            case "placement" -> placement(generationId, principal, string(input, "intent"),
                    string(input, "processId"));
            case "failure_trace" -> failureTrace(generationId, principal, string(input, "symptom"));
            case "input_acceptance" -> inputAcceptance(generationId, principal,
                    string(input, "processId"), input.get("inputs"));
            case "describe_process" -> describeProcess(generationId, principal,
                    string(input, "processId"));
            case "change_impact" -> changeImpact(generationId, principal,
                    string(input, "proposal"), string(input, "target"));
            default -> throw new IllegalArgumentException("Unknown service: " + serviceId);
        };
    }

    // ------------------------------------------------------------ helpers

    private String string(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private int integer(Map<String, Object> input, String key, int fallback) {
        Object value = input.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private AnswerModels.Builder base(String generationId) {
        return AnswerModels.Answer.builder()
                .requestId(UUID.randomUUID().toString())
                .freshness(evidenceService.freshness(generationId))
                .coverage(coverage(generationId));
    }

    /** Declares the supported analysis scope and the known gaps (BR-13). */
    private AnswerModels.Coverage coverage(String generationId) {
        List<String> gaps = jdbc.queryForList(
                "SELECT DISTINCT finding_type || ': ' || detail FROM coverage_finding "
                + "WHERE generation_id = ? AND finding_type IN "
                + "('unresolved_dynamic_call','unsupported_language','unsupported_construct','parse_failure') "
                + "ORDER BY 1 LIMIT 25", String.class, generationId);
        return new AnswerModels.Coverage(
                "Java/Spring subset: classes, methods, Spring MVC routes, resolved within-asset "
                + "invocations, @Value configuration uses, and JdbcTemplate statements with literal SQL.",
                gaps);
    }

    // ---------------------------------------------------------- navigate

    private Answer navigate(String generationId, Principal principal, String query) {
        if (query == null || query.isBlank()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No query supplied.").unknown("query is required.").build();
        }
        List<Map<String, Object>> nodes = traversal.resolveTarget(generationId, principal, query);
        if (nodes.isEmpty()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("Nothing in the authorized scope matches '" + query + "'.")
                    .unknown("No node named '" + query + "' exists in the published generation.")
                    .nextEvidence("Try the search service for free-text matching.")
                    .build();
        }
        AnswerModels.Builder builder = base(generationId).status(Status.ANSWERED)
                .summary("Found " + nodes.size() + " matching item(s) for '" + query + "'.");

        List<String> locationIds = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> node : nodes) {
            String locationId = node.get("location_id") == null ? null
                    : String.valueOf(node.get("location_id"));
            if (locationId != null) {
                locationIds.add(locationId);
            }
            builder.claim(new Claim("claim-" + index++,
                    String.valueOf(node.get("qualified_name")) + " is a "
                        + String.valueOf(node.get("node_type")) + " in "
                        + String.valueOf(node.get("asset_id")) + ".",
                    "derived", locationId == null ? List.of() : List.of(locationId), List.of()));
        }
        builder.evidence(evidenceService.load(principal, locationIds));
        builder.data(Map.of("matches", nodes));
        return builder.build();
    }

    // ------------------------------------------------------------ search

    /**
     * Hybrid retrieval across curated meaning and extracted structure.
     * Ranking uses PostgreSQL full-text search; when no vector extension is
     * configured this is keyword retrieval, reported as a degraded mode.
     */
    private Answer search(String generationId, Principal principal, String query,
                          String assetId, String scope) {
        if (query == null || query.isBlank()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No query supplied.").unknown("query is required.").build();
        }
        String mode = scope == null ? "both" : scope;
        String[] assets = principal.authorizedAssets().toArray(new String[0]);
        List<Map<String, Object>> meaningHits = List.of();
        List<Map<String, Object>> sourceHits = List.of();

        if (!"source".equals(mode)) {
            meaningHits = jdbc.queryForList(
                    "SELECT v.id, v.meaning_id, m.business_name, v.statement, v.status, "
                    + "       v.reviewer, v.reviewed_at, "
                    + "       ts_rank(v.search_vector, websearch_to_tsquery('english', ?)) AS rank "
                    + "FROM business_meaning_version v "
                    + "JOIN business_meaning m ON m.id = v.meaning_id "
                    + "WHERE v.status = 'reviewed' AND v.version = m.current_version "
                    + "  AND v.search_vector @@ websearch_to_tsquery('english', ?) "
                    + "ORDER BY rank DESC LIMIT 10", query, query);
        }
        if (!"meaning".equals(mode)) {
            sourceHits = jdbc.queryForList(
                    "SELECT n.id, n.node_type, n.name, n.qualified_name, n.asset_id, n.location_id, "
                    + "       ts_rank(n.search_vector, websearch_to_tsquery('english', ?)) AS rank "
                    + "FROM knowledge_node n "
                    + "WHERE n.generation_id = ? AND n.asset_id = ANY(?) "
                    + "  AND n.search_vector @@ websearch_to_tsquery('english', ?) "
                    + "ORDER BY rank DESC LIMIT 15",
                    query, generationId, assets, query);
        }

        if (meaningHits.isEmpty() && sourceHits.isEmpty()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No authorized meaning or source matches '" + query + "'.")
                    .unknown("Keyword retrieval found no match. Vocabulary that differs from "
                            + "the indexed terms may not match without semantic search.")
                    .nextEvidence("Try different business terms, or name the symbol directly.")
                    .build();
        }

        AnswerModels.Builder builder = base(generationId).status(Status.ANSWERED)
                .summary("Found " + meaningHits.size() + " reviewed statement(s) and "
                        + sourceHits.size() + " source item(s) for '" + query + "'.");

        int index = 1;
        List<String> locationIds = new ArrayList<>();
        for (Map<String, Object> hit : meaningHits) {
            builder.claim(new Claim("claim-" + index++, String.valueOf(hit.get("statement")),
                    "reviewed", List.of(),
                    List.of("Reviewed by " + hit.get("reviewer") + " on " + hit.get("reviewed_at") + ".")));
        }
        for (Map<String, Object> hit : sourceHits) {
            String locationId = hit.get("location_id") == null ? null
                    : String.valueOf(hit.get("location_id"));
            if (locationId != null) {
                locationIds.add(locationId);
            }
            builder.claim(new Claim("claim-" + index++,
                    hit.get("qualified_name") + " (" + hit.get("node_type") + ") in "
                        + hit.get("asset_id") + " matches the query.",
                    "derived", locationId == null ? List.of() : List.of(locationId), List.of()));
        }
        builder.evidence(evidenceService.load(principal, locationIds));
        builder.unknown("Retrieval is keyword-based full-text search. "
                + "No vector index is configured, so semantically similar wording "
                + "that shares no terms may be missed.");
        builder.data(Map.of("meaning", meaningHits, "source", sourceHits));
        return builder.build();
    }

    // ------------------------------------------------------------ detail

    private Answer detail(Principal principal, String locationId) {
        String generationId = store.activeGenerationId();
        if (locationId == null) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No location supplied.").unknown("locationId is required.").build();
        }
        AnswerModels.Evidence evidence = evidenceService.loadOne(principal, locationId);
        if (evidence == null) {
            // Indistinguishable responses for absent and unauthorized locations.
            return base(generationId).status(Status.BLOCKED)
                    .summary("That source location is not available in your authorized scope.")
                    .unknown("Location " + locationId + " is unknown or outside your asset scope.")
                    .build();
        }
        return base(generationId).status(Status.ANSWERED)
                .summary("Source at " + evidence.path() + " lines "
                        + evidence.startLine() + "-" + evidence.endLine()
                        + " (revision " + evidence.revision() + ").")
                .claim(new Claim("claim-1", "Exact stored source content at the recorded revision.",
                        "derived", List.of(locationId), List.of()))
                .evidence(evidence)
                .build();
    }

    // ------------------------------------------------------------ impact

    private Answer impact(String generationId, Principal principal, String target,
                          String direction, int maxDepth) {
        if (target == null || target.isBlank()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No target supplied.").unknown("target is required.").build();
        }
        List<Map<String, Object>> matches = traversal.resolveTarget(generationId, principal, target);
        if (matches.isEmpty()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No authorized item matches '" + target + "'.")
                    .unknown("'" + target + "' does not resolve to a node in the published generation.")
                    .nextEvidence("Use navigate or search to find the exact symbol name.")
                    .build();
        }

        Map<String, Object> start = matches.get(0);
        String startNodeId = String.valueOf(start.get("id"));
        String requested = direction == null ? "both" : direction;

        List<List<ImpactTraversal.Step>> inbound = "downstream".equals(requested)
                ? List.of() : traversal.traverse(generationId, principal, startNodeId, "inbound", maxDepth);
        List<List<ImpactTraversal.Step>> downstream = "inbound".equals(requested)
                ? List.of() : traversal.traverse(generationId, principal, startNodeId, "downstream", maxDepth);

        AnswerModels.Builder builder = base(generationId).status(Status.ANSWERED)
                .summary("Found " + inbound.size() + " inbound and " + downstream.size()
                        + " downstream dependency path(s) for " + start.get("qualified_name")
                        + " within depth " + maxDepth + ".");

        Set<String> locationIds = new LinkedHashSet<>();
        addPaths(builder, inbound, locationIds);
        addPaths(builder, downstream, locationIds);

        int index = 1;
        for (List<ImpactTraversal.Step> path : inbound) {
            ImpactTraversal.Step first = path.get(path.size() - 1);
            builder.claim(new Claim("claim-" + index++,
                    first.fromSymbol() + " reaches " + start.get("qualified_name")
                        + " through " + path.size() + " supported relationship(s).",
                    path.stream().anyMatch(s -> "inferred".equals(s.provenance())) ? "inferred" : "derived",
                    path.stream().map(ImpactTraversal.Step::locationId).filter(java.util.Objects::nonNull).toList(),
                    path.stream().filter(s -> s.inferenceReason() != null)
                        .map(ImpactTraversal.Step::inferenceReason).toList()));
        }

        builder.evidence(evidenceService.load(principal, List.copyOf(locationIds)));
        builder.unknown("Traversal covers statically supported relationships only. "
                + "Runtime dispatch, reflection, and configuration-driven wiring are not included.");
        if (principal.authorizedAssets().size() < countAllAssets()) {
            builder.unknown("Results are limited to your authorized assets; "
                    + "dependencies in other assets are not shown.");
        }
        builder.data(Map.of("target", start,
                "inboundCount", inbound.size(), "downstreamCount", downstream.size()));
        return builder.build();
    }

    private void addPaths(AnswerModels.Builder builder, List<List<ImpactTraversal.Step>> paths,
                          Set<String> locationIds) {
        for (List<ImpactTraversal.Step> path : paths) {
            List<AnswerModels.PathStep> steps = new ArrayList<>();
            for (ImpactTraversal.Step step : path) {
                if (step.locationId() != null) {
                    locationIds.add(step.locationId());
                }
                steps.add(new AnswerModels.PathStep(step.fromSymbol(), step.toSymbol(),
                        step.edgeType(), step.provenance(), step.inferenceReason(),
                        step.locationId() == null ? List.of() : List.of(step.locationId())));
            }
            builder.path(new AnswerModels.DependencyPath(steps, steps.size()));
        }
    }

    private int countAllAssets() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM asset", Integer.class);
        return count == null ? 0 : count;
    }

    // ----------------------------------------------------- configuration

    /** Approved snapshots only, with age. Never connects to a running system. */
    private Answer configuration(Principal principal, String key, String assetId) {
        String generationId = store.activeGenerationId();
        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.asset_id, s.config_key, s.value_type, s.value_text, s.source_path, "
                + "       s.location_id, s.snapshot_at, "
                + "       extract(epoch from (now() - s.snapshot_at)) AS age_seconds "
                + "FROM reference_snapshot s WHERE s.asset_id = ANY(?)");
        List<Object> args = new ArrayList<>();
        args.add(principal.authorizedAssets().toArray(new String[0]));
        if (key != null && !key.isBlank()) {
            sql.append(" AND s.config_key = ?");
            args.add(key);
        }
        if (assetId != null && !assetId.isBlank()) {
            sql.append(" AND s.asset_id = ?");
            args.add(assetId);
        }
        sql.append(" ORDER BY s.asset_id, s.config_key");

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        if (rows.isEmpty()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary(key == null
                            ? "No approved configuration snapshots are available in your scope."
                            : "No approved snapshot exists for '" + key + "'.")
                    .unknown("Only allowlisted configuration keys are snapshotted. "
                            + "A key absent here is either not approved or not present in source.")
                    .nextEvidence("A platform owner can add the key to the reference allowlist.")
                    .build();
        }

        AnswerModels.Builder builder = base(generationId).status(Status.ANSWERED)
                .summary("Returning " + rows.size() + " approved configuration snapshot(s).");
        List<String> locationIds = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> row : rows) {
            String locationId = row.get("location_id") == null ? null
                    : String.valueOf(row.get("location_id"));
            if (locationId != null) {
                locationIds.add(locationId);
            }
            long ageSeconds = row.get("age_seconds") == null ? 0
                    : ((Number) row.get("age_seconds")).longValue();
            builder.claim(new Claim("claim-" + index++,
                    row.get("config_key") + " is " + row.get("value_text")
                        + " in " + row.get("asset_id") + ".",
                    "derived", locationId == null ? List.of() : List.of(locationId),
                    List.of("Snapshot taken " + describeAge(ageSeconds)
                            + " from " + row.get("source_path")
                            + ". The running system may differ.")));
        }
        builder.evidence(evidenceService.load(principal, locationIds));
        builder.data(Map.of("snapshots", rows));
        return builder.build();
    }

    private String describeAge(long seconds) {
        if (seconds < 60) {
            return seconds + " second(s) ago";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " minute(s) ago";
        }
        if (seconds < 86400) {
            return (seconds / 3600) + " hour(s) ago";
        }
        return (seconds / 86400) + " day(s) ago";
    }

    // ----------------------------------------------------------- process

    /** Loads process stages the caller is authorized to see. */
    private List<Map<String, Object>> stages(String processId, Principal principal, String kind) {
        StringBuilder sql = new StringBuilder(
                "SELECT s.* FROM process_stage s JOIN business_process p ON p.id = s.process_id "
                + "WHERE (p.id = ? OR p.business_name ILIKE ?) "
                + "AND (s.asset_id IS NULL OR s.asset_id = ANY(?))");
        List<Object> args = new ArrayList<>();
        args.add(processId);
        args.add(processId);
        args.add(principal.authorizedAssets().toArray(new String[0]));
        if (kind != null) {
            sql.append(" AND s.stage_kind = ?");
            args.add(kind);
        }
        sql.append(" ORDER BY s.stage_order");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** Counts stages hidden from this caller by asset scope, for honest gaps. */
    private int hiddenStageCount(String processId, Principal principal) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM process_stage s JOIN business_process p ON p.id = s.process_id "
                + "WHERE (p.id = ? OR p.business_name ILIKE ?) "
                + "AND s.asset_id IS NOT NULL AND NOT (s.asset_id = ANY(?))",
                Integer.class, processId, processId,
                (Object) principal.authorizedAssets().toArray(new String[0]));
        return count == null ? 0 : count;
    }

    private Answer flow(String generationId, Principal principal, String processId) {
        return composeStages(generationId, principal, processId, null,
                "Ordered stages of the process.");
    }

    private Answer checks(String generationId, Principal principal, String processId) {
        return composeStages(generationId, principal, processId, "check",
                "Validations in process order with their enforcement locations.");
    }

    private Answer composeStages(String generationId, Principal principal, String processId,
                                 String kind, String heading) {
        if (processId == null || processId.isBlank()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No process supplied.").unknown("processId is required.").build();
        }
        List<Map<String, Object>> rows = stages(processId, principal, kind);
        if (rows.isEmpty()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No authorized process stages found for '" + processId + "'.")
                    .unknown("The process is unknown, has no stages of this kind, or its stages "
                            + "are outside your authorized scope.")
                    .build();
        }

        Map<String, Object> process = jdbc.queryForList(
                "SELECT id, business_name, order_basis, owner FROM business_process "
                + "WHERE id = ? OR business_name ILIKE ? LIMIT 1", processId, processId)
                .stream().findFirst().orElse(Map.of());

        AnswerModels.Builder builder = base(generationId).status(Status.ANSWERED)
                .summary(heading + " " + rows.size() + " stage(s) in '"
                        + process.getOrDefault("business_name", processId) + "'.");

        List<String> locationIds = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> stage : rows) {
            String symbol = stage.get("symbol_key") == null ? null
                    : String.valueOf(stage.get("symbol_key"));
            String locationId = symbol == null ? null
                    : evidenceService.locationForSymbol(generationId, principal, symbol);
            if (locationId != null) {
                locationIds.add(locationId);
            }
            List<String> limitations = new ArrayList<>();
            if (locationId == null && symbol != null) {
                limitations.add("The anchored symbol '" + symbol
                        + "' does not resolve in the current generation.");
            }
            if (stage.get("branch_condition") != null) {
                limitations.add("Applies when: " + stage.get("branch_condition") + ".");
            }
            builder.claim(new Claim("claim-" + index++,
                    "Stage " + stage.get("stage_order") + " (" + stage.get("stage_kind") + "): "
                        + stage.get("name") + " - " + stage.get("description"),
                    // Stage ordering is curated; its anchor is derived.
                    "reviewed", locationId == null ? List.of() : List.of(locationId), limitations));
        }

        builder.evidence(evidenceService.load(principal, locationIds));
        builder.unknown("Stage order is " + process.getOrDefault("order_basis", "curated")
                + ". Static analysis cannot establish exact runtime order, concurrency, "
                + "or all dynamic dispatch.");
        int hidden = hiddenStageCount(processId, principal);
        if (hidden > 0) {
            builder.unknown(hidden + " stage(s) belong to assets outside your authorized scope "
                    + "and are not shown.");
        }
        builder.data(Map.of("process", process, "stages", rows));
        return builder.build();
    }

    /** Known writes and state changes, from extracted persistence edges. */
    private Answer effects(String generationId, Principal principal, String processId, String symbol) {
        List<Map<String, Object>> writes;
        if (symbol != null && !symbol.isBlank()) {
            writes = jdbc.queryForList(
                    "SELECT s.qualified_name AS writer, t.name AS target, e.edge_type, "
                    + "       e.attributes->>'statement' AS statement, s.location_id, s.asset_id "
                    + "FROM knowledge_edge e "
                    + "JOIN knowledge_node s ON s.id = e.source_node_id "
                    + "JOIN knowledge_node t ON t.id = e.target_node_id "
                    + "WHERE e.generation_id = ? AND e.edge_type IN ('writes','reads') "
                    + "  AND s.asset_id = ANY(?) AND s.qualified_name LIKE ? "
                    + "ORDER BY s.qualified_name",
                    generationId, principal.authorizedAssets().toArray(new String[0]), "%" + symbol + "%");
        } else {
            writes = jdbc.queryForList(
                    "SELECT s.qualified_name AS writer, t.name AS target, e.edge_type, "
                    + "       e.attributes->>'statement' AS statement, s.location_id, s.asset_id "
                    + "FROM knowledge_edge e "
                    + "JOIN knowledge_node s ON s.id = e.source_node_id "
                    + "JOIN knowledge_node t ON t.id = e.target_node_id "
                    + "WHERE e.generation_id = ? AND e.edge_type = 'writes' AND s.asset_id = ANY(?) "
                    + "ORDER BY s.asset_id, s.qualified_name",
                    generationId, principal.authorizedAssets().toArray(new String[0]));
        }

        if (writes.isEmpty()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No known data effects found in your authorized scope.")
                    .unknown("Only persistence operations with literal SQL are extracted. "
                            + "Dynamically built statements are not represented.")
                    .build();
        }

        AnswerModels.Builder builder = base(generationId).status(Status.ANSWERED)
                .summary("Found " + writes.size() + " known data effect(s).");
        List<String> locationIds = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> write : writes) {
            String locationId = write.get("location_id") == null ? null
                    : String.valueOf(write.get("location_id"));
            if (locationId != null) {
                locationIds.add(locationId);
            }
            builder.claim(new Claim("claim-" + index++,
                    write.get("writer") + " " + write.get("edge_type") + " "
                        + write.get("target") + ".",
                    "derived", locationId == null ? List.of() : List.of(locationId),
                    write.get("statement") == null ? List.of()
                            : List.of("Statement: " + write.get("statement"))));
        }
        builder.evidence(evidenceService.load(principal, locationIds));
        builder.data(Map.of("effects", writes));
        return builder.build();
    }

    // ----------------------------------------------------------- explain

    /** Explains a symbol or reviewed concept, keeping derived and reviewed apart (BR-20). */
    private Answer explain(String generationId, Principal principal, String target) {
        if (target == null || target.isBlank()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No target supplied.").unknown("target is required.").build();
        }

        List<Map<String, Object>> meaning = jdbc.queryForList(
                "SELECT v.id, m.business_name, v.statement, v.reviewer, v.reviewed_at, v.owner "
                + "FROM business_meaning_version v JOIN business_meaning m ON m.id = v.meaning_id "
                + "WHERE v.status = 'reviewed' AND v.version = m.current_version "
                + "  AND (m.id = ? OR m.business_name ILIKE ? OR EXISTS ("
                + "      SELECT 1 FROM meaning_anchor a WHERE a.meaning_version_id = v.id "
                + "        AND a.symbol_key = ? AND a.asset_id = ANY(?)))",
                target, target, target, principal.authorizedAssets().toArray(new String[0]));

        List<Map<String, Object>> nodes = traversal.resolveTarget(generationId, principal, target);

        if (meaning.isEmpty() && nodes.isEmpty()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("Nothing authorized matches '" + target + "'.")
                    .unknown("No reviewed meaning and no extracted symbol matches that name.")
                    .nextEvidence("A reviewer can author business meaning anchored to this symbol.")
                    .build();
        }

        AnswerModels.Builder builder = base(generationId).status(Status.ANSWERED)
                .summary("Explanation for '" + target + "' from "
                        + meaning.size() + " reviewed statement(s) and "
                        + nodes.size() + " extracted item(s).");

        List<String> locationIds = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> row : meaning) {
            builder.claim(new Claim("claim-" + index++, String.valueOf(row.get("statement")),
                    "reviewed", List.of(),
                    List.of("Reviewed by " + row.get("reviewer") + " on " + row.get("reviewed_at")
                            + ", owned by " + row.get("owner") + ".")));
        }
        for (Map<String, Object> node : nodes) {
            String locationId = node.get("location_id") == null ? null
                    : String.valueOf(node.get("location_id"));
            if (locationId != null) {
                locationIds.add(locationId);
            }
            builder.claim(new Claim("claim-" + index++,
                    node.get("qualified_name") + " is a " + node.get("node_type")
                        + " extracted from " + node.get("asset_id") + ".",
                    "derived", locationId == null ? List.of() : List.of(locationId), List.of()));
        }
        if (meaning.isEmpty()) {
            builder.unknown("No reviewed business meaning is anchored to this symbol; "
                    + "only extracted structure is available.");
        }
        builder.evidence(evidenceService.load(principal, locationIds));
        return builder.build();
    }

    // ------------------------------------------------------------ status

    private Answer status(String generationId, Principal principal) {
        Map<String, Object> settings = jdbc.queryForList(
                "SELECT platform_owner, refresh_cadence FROM platform_settings WHERE id = 1")
                .stream().findFirst().orElse(Map.of());

        List<Map<String, Object>> assets = jdbc.queryForList(
                "SELECT a.id, a.business_name, a.owner, a.language, a.scope_status, "
                + "  (SELECT count(*) FROM knowledge_node n WHERE n.generation_id = ? "
                + "     AND n.asset_id = a.id) AS node_count "
                + "FROM asset a WHERE a.id = ANY(?) ORDER BY a.id",
                generationId, principal.authorizedAssets().toArray(new String[0]));

        Map<String, Object> job = jdbc.queryForList(
                "SELECT id, state, scope, started_at, finished_at, failure_reason "
                + "FROM refresh_job ORDER BY started_at DESC LIMIT 1")
                .stream().findFirst().orElse(Map.of());

        List<Map<String, Object>> findings = jdbc.queryForList(
                "SELECT finding_type, count(*) AS count FROM coverage_finding "
                + "WHERE generation_id = ? AND asset_id = ANY(?) GROUP BY finding_type ORDER BY 1",
                generationId, principal.authorizedAssets().toArray(new String[0]));

        Integer edgeCount = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_edge WHERE generation_id = ?",
                Integer.class, generationId);

        AnswerModels.Builder builder = base(generationId).status(Status.ANSWERED)
                .summary("Generation " + generationId + " is active with "
                        + assets.size() + " authorized asset(s) and " + edgeCount + " relationship(s).")
                .claim(new Claim("claim-1",
                        "The named platform owner is " + settings.getOrDefault("platform_owner", "unset")
                            + "; refresh cadence is " + settings.getOrDefault("refresh_cadence", "unset") + ".",
                        "reviewed", List.of(), List.of()));

        for (Map<String, Object> finding : findings) {
            builder.unknown(finding.get("count") + " " + finding.get("finding_type")
                    + " finding(s) recorded in this generation.");
        }

        builder.data(Map.of(
                "generation", generationId,
                "profile", properties.getProfile(),
                "modelConfigured", properties.getModel().isConfigured(),
                "assets", assets,
                "lastJob", job,
                "coverageFindings", findings,
                "edgeCount", edgeCount == null ? 0 : edgeCount));
        return builder.build();
    }

    // -------------------------------------------------------- composites

    /**
     * Recommends where a check belongs, based on the existing ordered checks.
     * Produces guidance and evidence only; it never generates a patch (BR-31).
     */
    private Answer placement(String generationId, Principal principal, String intent,
                             String processId) {
        String process = processId == null ? "proc-purchase-approval" : processId;
        List<Map<String, Object>> checkStages = stages(process, principal, "check");
        if (checkStages.isEmpty()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No authorized checks found in '" + process + "' to position against.")
                    .unknown("Placement guidance needs an existing ordered check sequence.")
                    .build();
        }

        Map<String, Object> last = checkStages.get(checkStages.size() - 1);
        Map<String, Object> first = checkStages.get(0);

        AnswerModels.Builder builder = base(generationId).status(Status.ANSWERED)
                .summary("Placement guidance for: " + (intent == null ? "the proposed check" : intent));

        List<String> locationIds = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> stage : checkStages) {
            String symbol = stage.get("symbol_key") == null ? null
                    : String.valueOf(stage.get("symbol_key"));
            String locationId = symbol == null ? null
                    : evidenceService.locationForSymbol(generationId, principal, symbol);
            if (locationId != null) {
                locationIds.add(locationId);
            }
            builder.claim(new Claim("claim-" + index++,
                    "Existing check at stage " + stage.get("stage_order") + ": " + stage.get("name")
                        + " in " + stage.get("asset_id") + ".",
                    "derived", locationId == null ? List.of() : List.of(locationId), List.of()));
        }

        // Guidance is explicitly a recommendation, never presented as a fact.
        builder.claim(new Claim("claim-" + index++,
                "Recommendation: place a cheap, local validation immediately after stage "
                    + first.get("stage_order") + " (" + first.get("name")
                    + ") so invalid requests are rejected before any handover. "
                    + "Place a check that depends on remote state after stage "
                    + last.get("stage_order") + " (" + last.get("name") + ").",
                "inferred", List.copyOf(new LinkedHashSet<>(locationIds)),
                List.of("This is a recommendation derived from existing check order, "
                        + "not an observed requirement. No source change is produced or applied.")));

        builder.evidence(evidenceService.load(principal, locationIds));
        builder.unknown("Placement cannot account for runtime ordering, transaction boundaries, "
                + "or concurrency that static analysis does not establish.");
        return builder.build();
    }

    /** Candidate static origins for a described failure. No runtime diagnosis. */
    private Answer failureTrace(String generationId, Principal principal, String symptom) {
        if (symptom == null || symptom.isBlank()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No symptom supplied.").unknown("symptom is required.").build();
        }
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT n.id, n.qualified_name, n.asset_id, n.location_id, n.node_type, "
                + "       ts_rank(n.search_vector, websearch_to_tsquery('english', ?)) AS rank "
                + "FROM knowledge_node n WHERE n.generation_id = ? AND n.asset_id = ANY(?) "
                + "  AND n.search_vector @@ websearch_to_tsquery('english', ?) "
                + "ORDER BY rank DESC LIMIT 10",
                symptom, generationId, principal.authorizedAssets().toArray(new String[0]), symptom);

        List<Map<String, Object>> failureStages = jdbc.queryForList(
                "SELECT s.* FROM process_stage s WHERE s.stage_kind IN ('failure','check') "
                + "AND (s.asset_id IS NULL OR s.asset_id = ANY(?)) "
                + "AND (s.name ILIKE ? OR s.description ILIKE ? OR s.branch_condition ILIKE ?) "
                + "ORDER BY s.stage_order",
                principal.authorizedAssets().toArray(new String[0]),
                "%" + symptom + "%", "%" + symptom + "%", "%" + symptom + "%");

        if (candidates.isEmpty() && failureStages.isEmpty()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No static candidate origin found for '" + symptom + "'.")
                    .unknown("No indexed symbol or documented failure path matches that description.")
                    .nextEvidence("Supply the exact message text or the symbol that produced it.")
                    .build();
        }

        AnswerModels.Builder builder = base(generationId).status(Status.PARTIAL)
                .summary("Found " + candidates.size() + " candidate location(s) and "
                        + failureStages.size() + " documented failure path(s) for '" + symptom + "'.");

        List<String> locationIds = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> candidate : candidates) {
            String locationId = candidate.get("location_id") == null ? null
                    : String.valueOf(candidate.get("location_id"));
            if (locationId != null) {
                locationIds.add(locationId);
            }
            builder.claim(new Claim("claim-" + index++,
                    candidate.get("qualified_name") + " in " + candidate.get("asset_id")
                        + " mentions terms from the reported symptom.",
                    "inferred", locationId == null ? List.of() : List.of(locationId),
                    List.of("Textual match only. This is a candidate, not a confirmed cause.")));
        }
        for (Map<String, Object> stage : failureStages) {
            builder.claim(new Claim("claim-" + index++,
                    "Documented path: " + stage.get("name") + " - " + stage.get("description"),
                    "reviewed", List.of(),
                    stage.get("branch_condition") == null ? List.of()
                            : List.of("Condition: " + stage.get("branch_condition"))));
        }
        builder.evidence(evidenceService.load(principal, locationIds));
        builder.unknown("CodeAtlas performs no runtime diagnosis: it has no logs, traces, "
                + "or production connection. These are static candidates only.");
        return builder.build();
    }

    /**
     * Evaluates synthetic inputs against extracted checks. Behaviour that the
     * analyzer cannot resolve is marked unresolved rather than assumed.
     */
    @SuppressWarnings("unchecked")
    private Answer inputAcceptance(String generationId, Principal principal, String processId,
                                   Object rawInputs) {
        if (processId == null || rawInputs == null) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("processId and inputs are both required.")
                    .unknown("Missing processId or inputs.").build();
        }
        Map<String, Object> inputs = rawInputs instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();

        List<Map<String, Object>> checkStages = stages(processId, principal, "check");
        if (checkStages.isEmpty()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No authorized checks found for '" + processId + "'.")
                    .unknown("Cannot evaluate inputs without extracted checks.").build();
        }

        // Approved configuration snapshots supply threshold values.
        Map<String, String> config = new LinkedHashMap<>();
        jdbc.queryForList("SELECT config_key, value_text FROM reference_snapshot "
                + "WHERE asset_id = ANY(?)", principal.authorizedAssets().toArray(new String[0]))
                .forEach(row -> config.put(String.valueOf(row.get("config_key")),
                        String.valueOf(row.get("value_text"))));

        AnswerModels.Builder builder = base(generationId);
        List<String> locationIds = new ArrayList<>();
        int index = 1;
        int unresolved = 0;

        for (Map<String, Object> stage : checkStages) {
            String symbol = stage.get("symbol_key") == null ? null
                    : String.valueOf(stage.get("symbol_key"));
            String locationId = symbol == null ? null
                    : evidenceService.locationForSymbol(generationId, principal, symbol);
            if (locationId != null) {
                locationIds.add(locationId);
            }

            String name = String.valueOf(stage.get("name")).toLowerCase(java.util.Locale.ROOT);
            String verdict;
            List<String> limitations = new ArrayList<>();

            if (name.contains("justification")) {
                Object justification = inputs.get("justification");
                verdict = (justification == null || String.valueOf(justification).isBlank())
                        ? "would REJECT: a justification is required"
                        : "would PASS: a justification is present";
            } else if (name.contains("cost centre") || name.contains("amount")) {
                Object costCentre = inputs.get("costCentre");
                Object amount = inputs.get("amount");
                boolean costCentreMissing = costCentre == null || String.valueOf(costCentre).isBlank();
                boolean amountInvalid = amount == null || toDouble(amount) <= 0;
                verdict = costCentreMissing ? "would REJECT: cost centre missing"
                        : amountInvalid ? "would REJECT: amount must be positive"
                        : "would PASS: cost centre present and amount positive";
            } else if (name.contains("threshold")) {
                String thresholdText = config.get("approval.threshold.amount");
                Object amount = inputs.get("amount");
                if (thresholdText == null || amount == null) {
                    verdict = "UNRESOLVED: the threshold snapshot or the amount is unavailable";
                    unresolved++;
                } else {
                    double threshold = Double.parseDouble(thresholdText);
                    verdict = toDouble(amount) > threshold
                            ? "would ESCALATE: amount exceeds the configured threshold of " + thresholdText
                            : "would PASS: amount is within the configured threshold of " + thresholdText;
                    limitations.add("Evaluated against the approved snapshot, "
                            + "which may differ from the running system.");
                }
            } else {
                verdict = "UNRESOLVED: this check depends on stored state that CodeAtlas does not hold";
                limitations.add("The platform holds no customer, transactional, or runtime data, "
                        + "so this check cannot be evaluated from inputs alone.");
                unresolved++;
            }

            builder.claim(new Claim("claim-" + index++,
                    "Stage " + stage.get("stage_order") + " (" + stage.get("name") + "): " + verdict,
                    verdict.startsWith("UNRESOLVED") ? "unknown" : "derived",
                    locationId == null ? List.of() : List.of(locationId), limitations));
        }

        builder.status(unresolved > 0 ? Status.PARTIAL : Status.ANSWERED)
                .summary("Evaluated " + checkStages.size() + " known check(s); "
                        + unresolved + " could not be resolved from the supplied inputs.");
        builder.evidence(evidenceService.load(principal, locationIds));
        if (unresolved > 0) {
            builder.unknown(unresolved + " check(s) depend on state the platform does not hold.");
        }
        return builder.build();
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Full walkthrough: stages, checks, effects, handovers, failures, and gaps. */
    private Answer describeProcess(String generationId, Principal principal, String processId) {
        Answer flowAnswer = composeStages(generationId, principal, processId, null,
                "Complete known process.");
        if (flowAnswer.status() == Status.UNKNOWN) {
            return flowAnswer;
        }
        Answer effectsAnswer = effects(generationId, principal, processId, null);

        AnswerModels.Builder builder = base(generationId).status(Status.ANSWERED)
                .summary(flowAnswer.summary())
                .claims(flowAnswer.claims())
                .evidence(flowAnswer.evidence())
                .unknowns(flowAnswer.unknowns());

        if (effectsAnswer.status() == Status.ANSWERED) {
            builder.claims(effectsAnswer.claims());
            builder.evidence(effectsAnswer.evidence());
        }
        builder.data(Map.of(
                "flow", flowAnswer.data() == null ? Map.of() : flowAnswer.data(),
                "effects", effectsAnswer.data() == null ? Map.of() : effectsAnswer.data()));
        return builder.build();
    }

    /**
     * The headline composite: what a proposed change affects.
     * Composes impact, checks, effects, configuration, and review guidance.
     */
    private Answer changeImpact(String generationId, Principal principal, String proposal,
                                String target) {
        if (proposal == null || proposal.isBlank()) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("No proposal supplied.").unknown("proposal is required.").build();
        }
        // Resolve the change target from the proposal when not given explicitly.
        String resolvedTarget = target;
        if (resolvedTarget == null || resolvedTarget.isBlank()) {
            resolvedTarget = guessTarget(generationId, principal, proposal);
        }
        if (resolvedTarget == null) {
            return base(generationId).status(Status.UNKNOWN)
                    .summary("Could not identify what the proposal changes.")
                    .unknown("No configuration key or symbol in scope matches '" + proposal + "'.")
                    .nextEvidence("Name the configuration key or symbol being changed.")
                    .build();
        }

        Answer impactAnswer = impact(generationId, principal, resolvedTarget, "inbound", 4);
        Answer configAnswer = configuration(principal, resolvedTarget, null);
        Answer checksAnswer = checks(generationId, principal, "proc-purchase-approval");
        Answer effectsAnswer = effects(generationId, principal, null, null);

        AnswerModels.Builder builder = base(generationId).status(Status.ANSWERED)
                .summary("Change impact for: " + proposal
                        + " (resolved target: " + resolvedTarget + ").");

        builder.claims(impactAnswer.claims()).evidence(impactAnswer.evidence())
                .paths(impactAnswer.paths()).unknowns(impactAnswer.unknowns());
        if (configAnswer.status() == Status.ANSWERED) {
            builder.claims(configAnswer.claims()).evidence(configAnswer.evidence());
        }
        if (checksAnswer.status() == Status.ANSWERED) {
            builder.claims(checksAnswer.claims()).evidence(checksAnswer.evidence());
        }
        if (effectsAnswer.status() == Status.ANSWERED) {
            builder.claims(effectsAnswer.claims());
        }

        // Review guidance is explicitly labelled as recommendation (README 7.3).
        builder.claim(new Claim("claim-review",
                "Recommended review: confirm each enforcement location above still behaves as "
                    + "intended, re-check the approved configuration snapshot after deployment, "
                    + "and exercise a value just below and just above the new boundary.",
                "inferred", List.of(),
                List.of("This is review guidance, not a verified safety assessment. "
                        + "CodeAtlas does not execute or test the described systems.")));

        builder.nextEvidence("Runtime behaviour, concurrency, and any dynamically dispatched "
                + "handler are outside static coverage and need manual review.");
        return builder.build();
    }

    /** Matches proposal text against configuration keys and symbols in scope. */
    private String guessTarget(String generationId, Principal principal, String proposal) {
        List<String> keys = jdbc.queryForList(
                "SELECT DISTINCT config_key FROM reference_snapshot WHERE asset_id = ANY(?)",
                String.class, (Object) principal.authorizedAssets().toArray(new String[0]));
        String lower = proposal.toLowerCase(java.util.Locale.ROOT);

        // Score each allowlisted key by how many of its dotted word-parts the
        // proposal mentions, so "the purchase approval threshold" resolves to
        // approval.threshold.amount without an exact key match.
        String best = null;
        int bestScore = 0;
        for (String key : keys) {
            String normalizedKey = key.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains(normalizedKey)) {
                return key;
            }
            int score = 0;
            for (String part : normalizedKey.split("[.\\-_]")) {
                // Ignore generic suffixes that match almost any sentence.
                if (part.length() < 4 || part.equals("amount") || part.equals("enabled")) {
                    continue;
                }
                if (lower.contains(part)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = key;
            }
        }
        // Require at least two matching parts so a single common word is not enough.
        if (best != null && bestScore >= 2) {
            return best;
        }
        // Fall back to full-text search over extracted nodes.
        List<String> nodes = jdbc.queryForList(
                "SELECT qualified_name FROM knowledge_node WHERE generation_id = ? "
                + "AND asset_id = ANY(?) AND search_vector @@ websearch_to_tsquery('english', ?) "
                + "ORDER BY ts_rank(search_vector, websearch_to_tsquery('english', ?)) DESC LIMIT 1",
                String.class, generationId, principal.authorizedAssets().toArray(new String[0]),
                proposal, proposal);
        return nodes.isEmpty() ? null : nodes.get(0);
    }
}

package com.codeatlas.refresh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.codeatlas.analysis.ConfigValue;
import com.codeatlas.analysis.ConfigurationAnalyzer;
import com.codeatlas.analysis.CoverageFinding;
import com.codeatlas.analysis.ExtractedModel;
import com.codeatlas.analysis.Identities;
import com.codeatlas.analysis.JavaSpringAnalyzer;
import com.codeatlas.analysis.SourceScanner;
import com.codeatlas.assets.AssetRegistry;
import com.codeatlas.knowledge.KnowledgeStore;

/**
 * Runs the staged refresh pipeline (README section 6).
 *
 * Stages: scan, extract, index, resolve anchors, validate, publish. A curated
 * anchor that cannot resolve fails the job and preserves the prior generation
 * rather than publishing stale business meaning (BR-18).
 */
@Service
public class RefreshService {

    private static final Logger log = LoggerFactory.getLogger(RefreshService.class);

    private final JdbcTemplate jdbc;
    private final AssetRegistry registry;
    private final KnowledgeStore store;
    private final ConfigurationAnalyzer configurationAnalyzer = new ConfigurationAnalyzer();

    public RefreshService(JdbcTemplate jdbc, AssetRegistry registry, KnowledgeStore store) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.store = store;
    }

    public record RefreshResult(String jobId, String state, String generationId,
                                String failureReason, Map<String, Object> counts) {
    }

    /** Runs a full-estate or single-asset refresh synchronously. */
    public RefreshResult refresh(String scope, String scopeAssetId, String requestedBy) {
        String jobId = "job-" + Instant.now().toEpochMilli();
        jdbc.update("INSERT INTO refresh_job (id, scope, scope_asset_id, state, requested_by) "
                + "VALUES (?, ?, ?, 'running', ?)", jobId, scope, scopeAssetId, requestedBy);

        String generationId = store.createCandidateGeneration("refresh " + jobId);
        jdbc.update("UPDATE refresh_job SET candidate_generation_id = ? WHERE id = ?",
                generationId, jobId);

        List<AssetRegistry.Asset> assets = "asset".equals(scope) && scopeAssetId != null
                ? List.of(registry.findById(scopeAssetId))
                : registry.findAll();

        Map<String, Object> counts = new LinkedHashMap<>();
        try {
            stage(jobId, 1, "scan-and-extract", () -> extractAll(assets, generationId, counts));
            stage(jobId, 2, "resolve-anchors", () -> resolveAnchors(generationId));
            stage(jobId, 3, "validate", () -> validateCandidate(generationId));

            // Publication only happens when every previous stage succeeded.
            store.publish(generationId);
            recordStage(jobId, 4, "publish", "succeeded", "Generation " + generationId + " active.");
            jdbc.update("UPDATE refresh_job SET state = 'succeeded', finished_at = now(), "
                    + "published_generation_id = ?, counts = ?::jsonb WHERE id = ?",
                    generationId, toJson(counts), jobId);
            return new RefreshResult(jobId, "succeeded", generationId, null, counts);

        } catch (RefreshFailure failure) {
            // The prior generation stays active and queryable (BR-70).
            store.markFailed(generationId, failure.getMessage());
            jdbc.update("UPDATE refresh_job SET state = 'failed', finished_at = now(), "
                    + "failure_reason = ?, counts = ?::jsonb WHERE id = ?",
                    failure.getMessage(), toJson(counts), jobId);
            log.warn("Refresh {} failed: {}", jobId, failure.getMessage());
            return new RefreshResult(jobId, "failed", generationId, failure.getMessage(), counts);
        }
    }

    /** A refresh stage failed; the candidate generation must not be published. */
    public static class RefreshFailure extends RuntimeException {
        public RefreshFailure(String message) {
            super(message);
        }
    }

    private void stage(String jobId, int order, String name, Runnable work) {
        recordStage(jobId, order, name, "running", null);
        try {
            work.run();
            recordStage(jobId, order, name, "succeeded", null);
        } catch (RefreshFailure e) {
            recordStage(jobId, order, name, "failed", e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            recordStage(jobId, order, name, "failed", e.toString());
            throw new RefreshFailure(name + " stage failed: " + e.getMessage());
        }
    }

    private void recordStage(String jobId, int order, String name, String state, String detail) {
        jdbc.update("INSERT INTO refresh_job_stage (job_id, stage_order, name, state, "
                + "started_at, finished_at, detail) VALUES (?, ?, ?, ?, now(), "
                + "CASE WHEN ? IN ('succeeded','failed') THEN now() ELSE NULL END, ?)",
                jobId, order, name, state, state, detail);
    }

    // ------------------------------------------------------- extraction

    private void extractAll(List<AssetRegistry.Asset> assets, String generationId,
                            Map<String, Object> counts) {
        int totalNodes = 0;
        int totalEdges = 0;
        int totalFindings = 0;
        List<String> supportedLanguages = List.of("java");

        for (AssetRegistry.Asset asset : assets) {
            if (asset == null || !"in_scope".equals(asset.scopeStatus())) {
                continue;
            }
            if (!supportedLanguages.contains(asset.language().toLowerCase(java.util.Locale.ROOT))) {
                // Registered but unsupported: visible coverage gap, not a failure (BR-13).
                store.saveCoverageFindings(generationId, List.of(new CoverageFinding(
                        asset.id(), CoverageFinding.UNSUPPORTED_LANGUAGE, null, null,
                        "Language '" + asset.language() + "' has no structural analyzer; "
                        + "the asset is registered with no derived structure.")));
                totalFindings++;
                continue;
            }

            Path root = registry.resolveSourcePath(asset.sourceLocator());
            ExtractedModel model = new ExtractedModel();

            SourceScanner.ScanResult scan;
            try {
                scan = SourceScanner.scan(root, asset.id());
            } catch (IOException e) {
                throw new RefreshFailure("Cannot scan asset " + asset.id() + ": " + e.getMessage());
            }
            scan.files().forEach(f -> model.countDiscovered());
            model.findings().addAll(scan.exclusions());
            scan.exclusions().forEach(f -> model.countExcluded());

            List<Path> javaFiles = scan.files().stream().filter(SourceScanner::isJava).toList();
            javaFiles.forEach(f -> model.countEligible());

            // The manifest hash pins exactly which file contents were indexed.
            String manifestHash = manifestHash(root, scan.files());
            String revisionId = "rev-" + Identities.shortHash(asset.id() + "|" + manifestHash);

            store.saveRevision(revisionId, asset.id(), manifestHash.substring(0, 12),
                    manifestHash, manifestHash, Instant.now());

            new JavaSpringAnalyzer(asset.id(), manifestHash, root, model).analyze(javaFiles);

            // Collect configuration locations first, then persist every location,
            // so reference snapshots can reference a row that already exists.
            List<PendingSnapshot> pendingSnapshots =
                    collectConfiguration(asset, root, scan.files(), revisionId, model);

            store.saveLocations(revisionId, model.locations());
            writeSnapshots(pendingSnapshots);
            store.saveNodes(generationId, model.nodes());
            store.saveEdges(generationId, model.edges());
            store.saveCoverageFindings(generationId, model.findings());

            // An application node anchors the asset in the graph.
            createApplicationNode(generationId, asset, model);

            totalNodes += model.nodes().size();
            totalEdges += model.edges().size();
            totalFindings += model.findings().size();

            counts.put(asset.id(), Map.of(
                    "discovered", model.discoveredFiles(),
                    "eligible", model.eligibleFiles(),
                    "parsed", model.parsedFiles(),
                    "excluded", model.excludedFiles(),
                    "failed", model.failedFiles(),
                    "revision", revisionId));
        }

        // Cross-asset links are matched after every asset is extracted (BR-10).
        int inferred = linkCrossAssetCalls(generationId);

        counts.put("nodes", totalNodes);
        counts.put("edges", totalEdges);
        counts.put("findings", totalFindings);
        counts.put("inferredCrossAssetEdges", inferred);
    }

    private void createApplicationNode(String generationId, AssetRegistry.Asset asset,
                                       ExtractedModel model) {
        String appNodeId = com.codeatlas.knowledge.KnowledgeStore.scoped(generationId,
                Identities.nodeId(asset.id(), "application", asset.id()));
        store.jdbc().update("INSERT INTO knowledge_node (id, generation_id, node_type, name, "
                + "qualified_name, asset_id, extractor, extractor_version, attributes) "
                + "VALUES (?, ?, 'application', ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (id) DO NOTHING",
                appNodeId, generationId, asset.businessName(), asset.id(), asset.id(),
                JavaSpringAnalyzer.EXTRACTOR, JavaSpringAnalyzer.VERSION,
                "{\"role\":\"" + asset.role() + "\"}");

        // Every extracted class belongs to its application.
        store.jdbc().update("INSERT INTO knowledge_edge (id, generation_id, edge_type, "
                + "source_node_id, target_node_id, provenance, evidence_ids) "
                + "SELECT 'e-' || substr(md5(n.id || ?), 1, 16), ?, 'belongs_to', n.id, ?, "
                + "'derived', '{}' FROM knowledge_node n "
                + "WHERE n.generation_id = ? AND n.asset_id = ? AND n.node_type = 'class' "
                + "ON CONFLICT (id) DO NOTHING",
                appNodeId, generationId, appNodeId, generationId, asset.id());
    }

    /**
     * Matches outbound HTTP integration paths against endpoints in other assets.
     * A URL/route match is evidence of a likely call, not a proven one, so the
     * edge is labelled inferred with a stated reason (BR-10).
     */
    private int linkCrossAssetCalls(String generationId) {
        List<Map<String, Object>> matches = store.jdbc().queryForList(
                "SELECT i.id AS integration_id, i.asset_id AS caller_asset, "
                + "       e.id AS endpoint_id, e.asset_id AS target_asset, "
                + "       i.attributes->>'routePath' AS route "
                + "FROM knowledge_node i "
                + "JOIN knowledge_node e ON e.generation_id = i.generation_id "
                + "  AND e.node_type = 'endpoint' "
                + "  AND e.attributes->>'route' = i.attributes->>'routePath' "
                + "  AND e.asset_id <> i.asset_id "
                + "WHERE i.generation_id = ? AND i.node_type = 'integration' "
                + "  AND i.attributes->>'routePath' IS NOT NULL",
                generationId);

        int created = 0;
        for (Map<String, Object> match : matches) {
            String edgeId = Identities.edgeId("calls_endpoint",
                    String.valueOf(match.get("integration_id")),
                    String.valueOf(match.get("endpoint_id")), "cross-asset");
            int rows = store.jdbc().update(
                    "INSERT INTO knowledge_edge (id, generation_id, edge_type, source_node_id, "
                    + "target_node_id, provenance, inference_reason, evidence_ids) "
                    + "VALUES (?, ?, 'calls_endpoint', ?, ?, 'inferred', ?, '{}') "
                    + "ON CONFLICT (id) DO NOTHING",
                    edgeId, generationId, match.get("integration_id"), match.get("endpoint_id"),
                    "Outbound route '" + match.get("route") + "' in " + match.get("caller_asset")
                    + " matches an endpoint declared by " + match.get("target_asset")
                    + ". No explicit contract establishes this call, so it is inferred "
                    + "from route equality alone.");
            created += rows;
        }
        return created;
    }

    /** Snapshots only allowlisted configuration keys (BR-41, CC-8). */
    /** A snapshot waiting for its source location row to be written. */
    private record PendingSnapshot(String id, String assetId, String configKey, String valueType,
                                   String valueText, String sourcePath, String revisionId,
                                   String locationId, String checksum) {
    }

    private List<PendingSnapshot> collectConfiguration(AssetRegistry.Asset asset, Path root,
                                                       List<Path> files, String revisionId,
                                                       ExtractedModel model) {
        List<PendingSnapshot> pending = new ArrayList<>();
        List<Map<String, Object>> allowlist = jdbc.queryForList(
                "SELECT config_key, value_type FROM reference_allowlist WHERE asset_id = ?",
                asset.id());
        if (allowlist.isEmpty()) {
            return pending;
        }

        for (Path file : files) {
            if (!SourceScanner.isConfiguration(file)) {
                continue;
            }
            String relative = Identities.normalizePath(root.relativize(file).toString());
            List<ConfigValue> values = configurationAnalyzer.read(asset.id(), file, relative);

            for (ConfigValue value : values) {
                Map<String, Object> approved = allowlist.stream()
                        .filter(a -> a.get("config_key").equals(value.key()))
                        .findFirst().orElse(null);
                if (approved == null) {
                    continue;
                }
                // Defence in depth: never snapshot a secret-like key even if allowlisted.
                if (configurationAnalyzer.isSecretLike(value.key())) {
                    model.findings().add(new CoverageFinding(asset.id(),
                            CoverageFinding.UNSUPPORTED_CONSTRUCT, relative, value.key(),
                            "Key rejected as secret-like despite allowlist entry."));
                    continue;
                }

                String locationId = Identities.locationId(asset.id(), revisionId, relative,
                        value.key(), value.line(), value.line());
                model.locations().add(new com.codeatlas.analysis.ExtractedLocation(
                        locationId, asset.id(), relative, value.key(),
                        value.line(), value.line(), Identities.sha256(value.value())));

                String snapshotId = "snap-" + Identities.shortHash(
                        asset.id() + "|" + value.key() + "|" + revisionId);
                pending.add(new PendingSnapshot(snapshotId, asset.id(), value.key(),
                        String.valueOf(approved.get("value_type")), value.value(), relative,
                        revisionId, locationId, Identities.sha256(value.value())));
            }
        }
        return pending;
    }

    /** Writes snapshots once their source locations exist. */
    private void writeSnapshots(List<PendingSnapshot> pending) {
        for (PendingSnapshot snapshot : pending) {
            jdbc.update("INSERT INTO reference_snapshot (id, asset_id, config_key, value_type, "
                    + "value_text, source_path, revision_id, location_id, checksum) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                    snapshot.id(), snapshot.assetId(), snapshot.configKey(), snapshot.valueType(),
                    snapshot.valueText(), snapshot.sourcePath(), snapshot.revisionId(),
                    snapshot.locationId(), snapshot.checksum());
        }
    }

    // --------------------------------------------------- anchor resolution

    /**
     * Re-resolves every curated anchor of every reviewed meaning against the
     * new extraction. An anchor that no longer resolves fails the refresh:
     * stale business meaning must never be published silently (BR-18).
     */
    private void resolveAnchors(String generationId) {
        List<Map<String, Object>> anchors = jdbc.queryForList(
                "SELECT a.id, a.asset_id, a.path, a.symbol_key, v.id AS version_id, "
                + "       v.meaning_id, v.version, m.business_name "
                + "FROM meaning_anchor a "
                + "JOIN business_meaning_version v ON v.id = a.meaning_version_id "
                + "JOIN business_meaning m ON m.id = v.meaning_id "
                + "WHERE v.status = 'reviewed' AND v.version = m.current_version");

        List<String> broken = new ArrayList<>();
        for (Map<String, Object> anchor : anchors) {
            String symbolKey = String.valueOf(anchor.get("symbol_key"));
            String assetId = String.valueOf(anchor.get("asset_id"));

            List<String> nodes = jdbc.queryForList(
                    "SELECT id FROM knowledge_node WHERE generation_id = ? AND asset_id = ? "
                    + "AND qualified_name = ?", String.class, generationId, assetId, symbolKey);

            if (nodes.isEmpty()) {
                jdbc.update("UPDATE meaning_anchor SET resolution_status = 'broken', "
                        + "resolved_location_id = NULL WHERE id = ?", anchor.get("id"));
                broken.add("meaning '" + anchor.get("business_name") + "' (" + anchor.get("meaning_id")
                        + " v" + anchor.get("version") + ") anchored to " + assetId + ":"
                        + symbolKey + " at " + anchor.get("path"));
            } else {
                jdbc.update("UPDATE meaning_anchor SET resolution_status = 'resolved', "
                        + "resolved_location_id = (SELECT location_id FROM knowledge_node WHERE id = ?) "
                        + "WHERE id = ?", nodes.get(0), anchor.get("id"));
            }
        }

        if (!broken.isEmpty()) {
            throw new RefreshFailure(
                    "Refresh failed: " + broken.size() + " curated anchor(s) no longer resolve. "
                    + String.join("; ", broken)
                    + ". The previous generation remains active; dependent answers are blocked "
                    + "until the meaning is re-reviewed against the new source.");
        }
    }

    /** Structural sanity checks before publication (BR-70). */
    private void validateCandidate(String generationId) {
        Integer orphanEdges = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_edge e WHERE e.generation_id = ? AND ("
                + "  NOT EXISTS (SELECT 1 FROM knowledge_node n WHERE n.id = e.source_node_id) "
                + "  OR NOT EXISTS (SELECT 1 FROM knowledge_node n WHERE n.id = e.target_node_id))",
                Integer.class, generationId);
        if (orphanEdges != null && orphanEdges > 0) {
            throw new RefreshFailure("Candidate generation has " + orphanEdges
                    + " edge(s) with missing endpoints.");
        }

        Integer nodeCount = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_node WHERE generation_id = ?",
                Integer.class, generationId);
        if (nodeCount == null || nodeCount == 0) {
            throw new RefreshFailure("Candidate generation contains no nodes; "
                    + "refusing to publish an empty knowledge graph.");
        }
    }

    // ----------------------------------------------------------- utilities

    /** Hashes file contents in a stable order to pin the indexed revision. */
    private String manifestHash(Path root, List<Path> files) {
        StringBuilder manifest = new StringBuilder();
        for (Path file : files) {
            try {
                String relative = Identities.normalizePath(root.relativize(file).toString());
                String content = Files.readString(file, StandardCharsets.UTF_8);
                manifest.append(relative).append(':').append(Identities.sha256(content)).append('\n');
            } catch (IOException e) {
                manifest.append(file).append(":unreadable\n");
            }
        }
        return Identities.sha256(manifest.toString());
    }

    private String toJson(Map<String, Object> counts) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(counts);
        } catch (Exception e) {
            return "{}";
        }
    }
}

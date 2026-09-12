package com.codeatlas.knowledge;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.codeatlas.analysis.ExtractedEdge;
import com.codeatlas.analysis.ExtractedLocation;
import com.codeatlas.analysis.ExtractedNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads and writes the versioned knowledge store.
 *
 * Generations are published atomically: a candidate is written, validated, and
 * only then promoted to active in a single transaction (BR-66).
 */
@Repository
public class KnowledgeStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ generations

    public String createCandidateGeneration(String notes) {
        String id = "g-" + Instant.now().toEpochMilli();
        jdbc.update("INSERT INTO knowledge_generation (id, state, notes) VALUES (?, 'candidate', ?)",
                id, notes);
        return id;
    }

    public String activeGenerationId() {
        List<String> found = jdbc.queryForList(
                "SELECT id FROM knowledge_generation WHERE state = 'active' "
                + "ORDER BY published_at DESC LIMIT 1", String.class);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Promotes a validated candidate and retires the previous active generation.
     * Runs in one transaction so answers never observe a mixed state.
     */
    @Transactional
    public void publish(String candidateGenerationId) {
        jdbc.update("UPDATE knowledge_generation SET state = 'historical' WHERE state = 'active'");
        jdbc.update("UPDATE knowledge_generation SET state = 'active', published_at = now() "
                + "WHERE id = ?", candidateGenerationId);
        jdbc.update("INSERT INTO platform_settings (id, platform_owner, refresh_cadence, active_generation_id) "
                + "VALUES (1, '', '', ?) ON CONFLICT (id) DO UPDATE SET active_generation_id = ?",
                candidateGenerationId, candidateGenerationId);
    }

    @Transactional
    public void markFailed(String generationId, String reason) {
        jdbc.update("UPDATE knowledge_generation SET state = 'failed', notes = ? WHERE id = ?",
                reason, generationId);
    }

    // ------------------------------------------------------------- writing

    public void saveRevision(String id, String assetId, String label, String digest,
                             String manifestHash, Instant observedAt) {
        jdbc.update("INSERT INTO source_revision (id, asset_id, revision_label, content_digest, "
                + "manifest_hash, observed_at) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (asset_id, content_digest) DO UPDATE SET indexed_at = now()",
                id, assetId, label, digest, manifestHash, Timestamp.from(observedAt));
    }

    public void saveLocations(String revisionId, Collection<ExtractedLocation> locations) {
        List<Object[]> batch = locations.stream().distinct()
                .map(l -> new Object[]{l.id(), l.assetId(), revisionId, l.path(),
                        l.symbolKey(), l.startLine(), l.endLine(), l.contentHash()})
                .toList();
        jdbc.batchUpdate("INSERT INTO source_location (id, asset_id, revision_id, path, "
                + "symbol_key, start_line, end_line, content_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (id) DO NOTHING", batch);
    }

    public void saveNodes(String generationId, Collection<ExtractedNode> nodes) {
        List<Object[]> batch = new ArrayList<>();
        for (ExtractedNode n : nodes) {
            batch.add(new Object[]{n.id(), generationId, n.type().wire(), n.name(),
                    n.qualifiedName(), n.assetId(), n.locationId(), n.extractor(),
                    n.extractorVersion(), writeJson(n.attributes())});
        }
        jdbc.batchUpdate("INSERT INTO knowledge_node (id, generation_id, node_type, name, "
                + "qualified_name, asset_id, location_id, extractor, extractor_version, attributes) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (id) DO NOTHING", batch);
    }

    public void saveEdges(String generationId, Collection<ExtractedEdge> edges) {
        List<Object[]> batch = new ArrayList<>();
        for (ExtractedEdge e : edges) {
            batch.add(new Object[]{e.id(), generationId, e.type().wire(), e.sourceNodeId(),
                    e.targetNodeId(), e.provenance().wire(), e.inferenceReason(),
                    e.evidenceIds().toArray(new String[0]), writeJson(e.attributes())});
        }
        jdbc.batchUpdate("INSERT INTO knowledge_edge (id, generation_id, edge_type, source_node_id, "
                + "target_node_id, provenance, inference_reason, evidence_ids, attributes) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (id) DO NOTHING", batch);
    }

    public void saveCoverageFindings(String generationId,
                                     Collection<com.codeatlas.analysis.CoverageFinding> findings) {
        List<Object[]> batch = findings.stream()
                .map(f -> new Object[]{generationId, f.assetId(), f.findingType(),
                        f.path(), f.symbolKey(), f.detail()})
                .toList();
        jdbc.batchUpdate("INSERT INTO coverage_finding (generation_id, asset_id, finding_type, "
                + "path, symbol_key, detail) VALUES (?, ?, ?, ?, ?, ?)", batch);
    }

    private String writeJson(Map<String, Object> attributes) {
        try {
            return objectMapper.writeValueAsString(attributes == null ? Map.of() : attributes);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ------------------------------------------------------------- reading

    public static final RowMapper<GraphNode> NODE_MAPPER = (rs, rowNum) -> new GraphNode(
            rs.getString("id"), rs.getString("node_type"), rs.getString("name"),
            rs.getString("qualified_name"), rs.getString("asset_id"),
            rs.getString("location_id"), rs.getString("attributes"));

    /** All scope filtering happens in SQL so unauthorized assets never load. */
    public List<GraphNode> findNodes(String generationId, List<String> authorizedAssets,
                                     String nodeType, int limit) {
        if (authorizedAssets.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM knowledge_node WHERE generation_id = ? AND asset_id = ANY(?)");
        List<Object> args = new ArrayList<>();
        args.add(generationId);
        args.add(authorizedAssets.toArray(new String[0]));
        if (nodeType != null) {
            sql.append(" AND node_type = ?");
            args.add(nodeType);
        }
        sql.append(" ORDER BY qualified_name LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), NODE_MAPPER, args.toArray());
    }

    public GraphNode findNodeById(String generationId, List<String> authorizedAssets, String nodeId) {
        List<GraphNode> found = jdbc.query(
                "SELECT * FROM knowledge_node WHERE generation_id = ? AND id = ? AND asset_id = ANY(?)",
                NODE_MAPPER, generationId, nodeId, authorizedAssets.toArray(new String[0]));
        return found.isEmpty() ? null : found.get(0);
    }

    public record GraphNode(String id, String nodeType, String name, String qualifiedName,
                            String assetId, String locationId, String attributesJson) {
    }

    public record GraphEdge(String id, String edgeType, String sourceNodeId, String targetNodeId,
                            String provenance, String inferenceReason, List<String> evidenceIds,
                            String attributesJson) {
    }

    public static final RowMapper<GraphEdge> EDGE_MAPPER = (rs, rowNum) -> {
        Array array = rs.getArray("evidence_ids");
        List<String> evidence = array == null ? List.of() : List.of((String[]) array.getArray());
        return new GraphEdge(rs.getString("id"), rs.getString("edge_type"),
                rs.getString("source_node_id"), rs.getString("target_node_id"),
                rs.getString("provenance"), rs.getString("inference_reason"),
                evidence, rs.getString("attributes"));
    };

    public JdbcTemplate jdbc() {
        return jdbc;
    }
}

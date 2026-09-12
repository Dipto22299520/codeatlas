package com.codeatlas.answers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.codeatlas.assets.AssetRegistry;
import com.codeatlas.security.Principal;

/**
 * Resolves stored source locations to exact excerpts at their recorded revision.
 *
 * Reads are scope-filtered in SQL, so an unauthorized asset cannot leak through
 * evidence retrieval (README section 13).
 */
@Service
public class EvidenceService {

    private final JdbcTemplate jdbc;
    private final AssetRegistry registry;

    public EvidenceService(JdbcTemplate jdbc, AssetRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    /** Loads evidence records for the given location ids, honouring caller scope. */
    public List<AnswerModels.Evidence> load(Principal principal, List<String> locationIds) {
        if (locationIds.isEmpty() || principal.authorizedAssets().isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT l.id, l.asset_id, l.path, l.symbol_key, l.start_line, l.end_line, "
                + "       r.content_digest, a.source_locator "
                + "FROM source_location l "
                + "JOIN source_revision r ON r.id = l.revision_id "
                + "JOIN asset a ON a.id = l.asset_id "
                + "WHERE l.id = ANY(?) AND l.asset_id = ANY(?)",
                locationIds.toArray(new String[0]),
                principal.authorizedAssets().toArray(new String[0]));

        List<AnswerModels.Evidence> evidence = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String excerpt = readExcerpt(
                    String.valueOf(row.get("source_locator")),
                    String.valueOf(row.get("path")),
                    ((Number) row.get("start_line")).intValue(),
                    ((Number) row.get("end_line")).intValue());
            evidence.add(new AnswerModels.Evidence(
                    String.valueOf(row.get("id")),
                    String.valueOf(row.get("asset_id")),
                    String.valueOf(row.get("content_digest")).substring(0, 12),
                    String.valueOf(row.get("path")),
                    ((Number) row.get("start_line")).intValue(),
                    ((Number) row.get("end_line")).intValue(),
                    row.get("symbol_key") == null ? null : String.valueOf(row.get("symbol_key")),
                    excerpt));
        }
        return evidence;
    }

    /** Loads a single evidence record, or null when absent or out of scope. */
    public AnswerModels.Evidence loadOne(Principal principal, String locationId) {
        List<AnswerModels.Evidence> found = load(principal, List.of(locationId));
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Reads the recorded line span from the read-only source mount.
     * The platform never writes to these paths (DR-2).
     */
    private String readExcerpt(String sourceLocator, String relativePath, int startLine, int endLine) {
        try {
            Path root = registry.resolveSourcePath(sourceLocator);
            Path file = root.resolve(relativePath).normalize();
            if (!file.startsWith(root) || !Files.isReadable(file)) {
                return "[source unavailable]";
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(1, startLine);
            int to = Math.min(lines.size(), endLine);
            StringBuilder builder = new StringBuilder();
            for (int i = from; i <= to; i++) {
                builder.append(lines.get(i - 1)).append('\n');
            }
            return builder.toString();
        } catch (IOException | RuntimeException e) {
            return "[source unavailable: " + e.getClass().getSimpleName() + "]";
        }
    }

    /** Location ids for a qualified symbol in the active generation. */
    public String locationForSymbol(String generationId, Principal principal, String qualifiedName) {
        List<String> found = jdbc.queryForList(
                "SELECT location_id FROM knowledge_node WHERE generation_id = ? "
                + "AND qualified_name = ? AND asset_id = ANY(?) AND location_id IS NOT NULL LIMIT 1",
                String.class, generationId, qualifiedName,
                (Object) principal.authorizedAssets().toArray(new String[0]));
        return found.isEmpty() ? null : found.get(0);
    }

    /** Freshness of the active generation for answer headers. */
    public AnswerModels.Freshness freshness(String generationId) {
        Map<String, Object> row = jdbc.queryForList(
                "SELECT g.id, g.published_at, max(r.observed_at) AS observed "
                + "FROM knowledge_generation g LEFT JOIN source_revision r ON true "
                + "WHERE g.id = ? GROUP BY g.id, g.published_at", generationId)
                .stream().findFirst().orElse(new LinkedHashMap<>());
        Object observed = row.get("observed");
        return new AnswerModels.Freshness(generationId,
                observed == null ? null : observed.toString(), false);
    }
}

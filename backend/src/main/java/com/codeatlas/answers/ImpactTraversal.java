package com.codeatlas.answers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.codeatlas.security.Principal;

/**
 * Bounded dependency traversal over the published knowledge graph.
 *
 * Depth and result limits are enforced, and caller scope is applied inside the
 * SQL so unauthorized assets are never traversed (README 7.2).
 */
@Service
public class ImpactTraversal {

    private static final int MAX_DEPTH_LIMIT = 6;
    private static final int MAX_PATHS = 50;

    private final JdbcTemplate jdbc;

    public ImpactTraversal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Step(String fromNodeId, String fromSymbol, String toNodeId, String toSymbol,
                       String edgeType, String provenance, String inferenceReason,
                       String locationId) {
    }

    /**
     * Walks inbound (callers of) or downstream (called by) edges from a start node.
     * Returns concrete paths rather than a flat set, so the UI can show how a
     * dependency is reached.
     */
    public List<List<Step>> traverse(String generationId, Principal principal, String startNodeId,
                                     String direction, int maxDepth) {
        int depthLimit = Math.min(Math.max(maxDepth, 1), MAX_DEPTH_LIMIT);
        List<List<Step>> paths = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(startNodeId);

        Deque<List<Step>> frontier = new ArrayDeque<>();
        for (Step step : neighbours(generationId, principal, startNodeId, direction)) {
            frontier.add(List.of(step));
        }

        while (!frontier.isEmpty() && paths.size() < MAX_PATHS) {
            List<Step> path = frontier.poll();
            Step last = path.get(path.size() - 1);
            String frontierNode = "inbound".equals(direction) ? last.fromNodeId() : last.toNodeId();

            paths.add(path);

            if (path.size() >= depthLimit || !visited.add(frontierNode)) {
                continue;
            }
            for (Step next : neighbours(generationId, principal, frontierNode, direction)) {
                List<Step> extended = new ArrayList<>(path);
                extended.add(next);
                frontier.add(extended);
            }
        }
        return paths;
    }

    /** One hop. Scope filtering happens in SQL, before any row is returned. */
    private List<Step> neighbours(String generationId, Principal principal, String nodeId,
                                  String direction) {
        String[] scope = principal.authorizedAssets().toArray(new String[0]);
        String sql;
        if ("inbound".equals(direction)) {
            sql = "SELECT e.id, e.edge_type, e.provenance, e.inference_reason, "
                + "       s.id AS from_id, s.qualified_name AS from_symbol, s.location_id AS loc, "
                + "       t.id AS to_id, t.qualified_name AS to_symbol "
                + "FROM knowledge_edge e "
                + "JOIN knowledge_node s ON s.id = e.source_node_id "
                + "JOIN knowledge_node t ON t.id = e.target_node_id "
                + "WHERE e.generation_id = ? AND e.target_node_id = ? "
                + "  AND s.asset_id = ANY(?) AND t.asset_id = ANY(?) "
                + "  AND e.edge_type IN ('invokes','calls_endpoint','uses_config','exposes') "
                + "ORDER BY s.qualified_name";
        } else {
            sql = "SELECT e.id, e.edge_type, e.provenance, e.inference_reason, "
                + "       s.id AS from_id, s.qualified_name AS from_symbol, s.location_id AS loc, "
                + "       t.id AS to_id, t.qualified_name AS to_symbol "
                + "FROM knowledge_edge e "
                + "JOIN knowledge_node s ON s.id = e.source_node_id "
                + "JOIN knowledge_node t ON t.id = e.target_node_id "
                + "WHERE e.generation_id = ? AND e.source_node_id = ? "
                + "  AND s.asset_id = ANY(?) AND t.asset_id = ANY(?) "
                + "  AND e.edge_type IN ('invokes','calls_endpoint','reads','writes','uses_config') "
                + "ORDER BY t.qualified_name";
        }

        List<Map<String, Object>> rows = jdbc.queryForList(sql, generationId, nodeId, scope, scope);
        List<Step> steps = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            steps.add(new Step(
                    String.valueOf(row.get("from_id")), String.valueOf(row.get("from_symbol")),
                    String.valueOf(row.get("to_id")), String.valueOf(row.get("to_symbol")),
                    String.valueOf(row.get("edge_type")), String.valueOf(row.get("provenance")),
                    row.get("inference_reason") == null ? null : String.valueOf(row.get("inference_reason")),
                    row.get("loc") == null ? null : String.valueOf(row.get("loc"))));
        }
        return steps;
    }

    /** Resolves a business or technical name to a node in the active generation. */
    public List<Map<String, Object>> resolveTarget(String generationId, Principal principal,
                                                   String target) {
        return jdbc.queryForList(
                "SELECT id, node_type, name, qualified_name, asset_id, location_id "
                + "FROM knowledge_node WHERE generation_id = ? AND asset_id = ANY(?) "
                + "  AND (qualified_name = ? OR name = ? OR qualified_name LIKE ?) "
                + "ORDER BY CASE WHEN qualified_name = ? THEN 0 ELSE 1 END, qualified_name LIMIT 10",
                generationId, principal.authorizedAssets().toArray(new String[0]),
                target, target, "%" + target, target);
    }
}

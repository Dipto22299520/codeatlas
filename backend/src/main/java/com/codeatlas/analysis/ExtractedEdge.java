package com.codeatlas.analysis;

import java.util.List;
import java.util.Map;

import com.codeatlas.knowledge.EdgeType;
import com.codeatlas.knowledge.Provenance;

public record ExtractedEdge(
        String id,
        EdgeType type,
        String sourceNodeId,
        String targetNodeId,
        Provenance provenance,
        String inferenceReason,
        List<String> evidenceIds,
        Map<String, Object> attributes) {
}

package com.codeatlas.analysis;

import java.util.Map;

import com.codeatlas.knowledge.NodeType;

public record ExtractedNode(
        String id,
        NodeType type,
        String name,
        String qualifiedName,
        String assetId,
        String locationId,
        String extractor,
        String extractorVersion,
        Map<String, Object> attributes) {
}

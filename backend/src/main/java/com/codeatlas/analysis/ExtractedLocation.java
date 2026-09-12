package com.codeatlas.analysis;

/** A revision-specific source span. Evidence is always revision-specific. */
public record ExtractedLocation(
        String id,
        String assetId,
        String path,
        String symbolKey,
        int startLine,
        int endLine,
        String contentHash) {
}

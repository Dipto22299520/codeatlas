package com.codeatlas.knowledge;

/**
 * Provenance labels (README section 5). Confidence is an evidence-based
 * classification with an explanation, never a model-generated probability.
 */
public enum Provenance {
    /** Supported by a named deterministic extractor. */
    DERIVED("derived"),
    /** Approved interpretation with reviewer and date. */
    REVIEWED("reviewed"),
    /** Plausible relationship with explicit evidence and limitations. */
    INFERRED("inferred"),
    /** Insufficient evidence or unsupported analysis. */
    UNKNOWN("unknown");

    private final String wire;

    Provenance(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}

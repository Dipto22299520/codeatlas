package com.codeatlas.analysis;

/** Honest coverage reporting: what was not understood (BR-13, CC-3). */
public record CoverageFinding(
        String assetId,
        String findingType,
        String path,
        String symbolKey,
        String detail) {

    public static final String UNSUPPORTED_CONSTRUCT = "unsupported_construct";
    public static final String UNRESOLVED_DYNAMIC_CALL = "unresolved_dynamic_call";
    public static final String PARSE_FAILURE = "parse_failure";
    public static final String EXCLUDED_FILE = "excluded_file";
    public static final String UNSUPPORTED_LANGUAGE = "unsupported_language";
}

package com.codeatlas.analysis;

/** A configuration key/value observed in a source configuration file. */
public record ConfigValue(
        String assetId,
        String key,
        String value,
        String valueType,
        String path,
        int line,
        String locationId) {
}

package com.codeatlas.knowledge;

/** Central edge types (README section 5). Unknown types fail validation. */
public enum EdgeType {
    CONTAINS("contains"),
    BELONGS_TO("belongs_to"),
    INVOKES("invokes"),
    READS("reads"),
    WRITES("writes"),
    EXPOSES("exposes"),
    CALLS_ENDPOINT("calls_endpoint"),
    USES_CONFIG("uses_config");

    private final String wire;

    EdgeType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static EdgeType fromWire(String value) {
        for (EdgeType t : values()) {
            if (t.wire.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown edge type: " + value);
    }
}

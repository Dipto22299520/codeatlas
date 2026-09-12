package com.codeatlas.knowledge;

/** Central node types (README section 5). Unknown types fail validation. */
public enum NodeType {
    APPLICATION("application"),
    MODULE("module"),
    CLASS("class"),
    METHOD("method"),
    ENDPOINT("endpoint"),
    DATASTORE("datastore"),
    TABLE("table"),
    INTEGRATION("integration"),
    CONFIGURATION_ITEM("configuration_item"),
    SOURCE_DOCUMENT("source_document");

    private final String wire;

    NodeType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static NodeType fromWire(String value) {
        for (NodeType t : values()) {
            if (t.wire.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown node type: " + value);
    }
}

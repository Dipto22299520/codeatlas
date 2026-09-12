package com.codeatlas.answers;

import java.util.List;
import java.util.Map;

/**
 * One authoritative answer-service definition (README section 8, BR-51).
 *
 * REST discovery and assistant tool definitions are both generated from these
 * records, so registering a service exposes it in every access mode without
 * editing any transport (BR-64).
 */
public record ServiceDefinition(
        String id,
        String description,
        String whenToUse,
        String distinction,
        List<Parameter> parameters,
        String requiredRole,
        boolean readOnly,
        int maxResults) {

    public record Parameter(String name, String type, String description, boolean required) {
    }

    /** JSON Schema for the assistant transport, derived from the parameters. */
    public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        List<String> required = new java.util.ArrayList<>();
        for (Parameter parameter : parameters) {
            properties.put(parameter.name(), Map.of(
                    "type", parameter.type(),
                    "description", parameter.description()));
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required);
    }
}

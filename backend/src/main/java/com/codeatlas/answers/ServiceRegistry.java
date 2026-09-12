package com.codeatlas.answers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.codeatlas.answers.ServiceDefinition.Parameter;

/**
 * The single registry of answer services (README section 8).
 *
 * Every access mode - REST discovery, REST invocation, and the assistant
 * interface - reads these definitions. Adding a service here exposes it
 * everywhere (BR-64, CC-7).
 */
@Component
public class ServiceRegistry {

    private final Map<String, ServiceDefinition> definitions = new LinkedHashMap<>();

    public ServiceRegistry() {
        // ---------------------------------------------------- primitives
        register(new ServiceDefinition("navigate",
                "Find an asset, symbol, capability, process, or business rule by name.",
                "Use when the caller names a thing and wants to locate it.",
                "Unlike search, this resolves a specific identifier rather than ranking free text.",
                List.of(new Parameter("query", "string", "Name or identifier to locate.", true)),
                "READER", true, 25));

        register(new ServiceDefinition("impact",
                "Traverse supported inbound and downstream dependencies of a symbol, with paths.",
                "Use to answer what depends on, or is depended upon by, a piece of software.",
                "Unlike change_impact, this returns raw dependency paths without composing review guidance.",
                List.of(new Parameter("target", "string", "Qualified symbol or configuration key.", true),
                        new Parameter("direction", "string", "inbound, downstream, or both.", false),
                        new Parameter("maxDepth", "integer", "Traversal depth limit (default 4).", false)),
                "READER", true, 100));

        register(new ServiceDefinition("flow",
                "Return the ordered stages of a business process, with branches and handovers.",
                "Use when the caller asks how a process works end to end.",
                "Unlike checks, this returns every stage kind, not only validations.",
                List.of(new Parameter("processId", "string", "Process identifier or business name.", true)),
                "READER", true, 50));

        register(new ServiceDefinition("checks",
                "List the validations in a process in order, with their enforcement locations.",
                "Use when the caller asks what is validated, or where a rule is enforced.",
                "Unlike flow, this returns only check stages and their source evidence.",
                List.of(new Parameter("processId", "string", "Process identifier or business name.", true)),
                "READER", true, 50));

        register(new ServiceDefinition("effects",
                "Identify known writes and state changes performed by a process or symbol.",
                "Use when the caller asks what data a process changes.",
                "Unlike checks, this reports persistence and state effects rather than validations.",
                List.of(new Parameter("processId", "string", "Process identifier or business name.", false),
                        new Parameter("symbol", "string", "Qualified symbol to inspect.", false)),
                "READER", true, 50));

        register(new ServiceDefinition("explain",
                "Explain a source item or a reviewed business concept, with provenance.",
                "Use when the caller asks what something means or does.",
                "Unlike detail, this returns an interpretation rather than raw source text.",
                List.of(new Parameter("target", "string", "Qualified symbol or business meaning id.", true)),
                "READER", true, 20));

        register(new ServiceDefinition("placement",
                "Recommend where a new check belongs, supported by process flow evidence.",
                "Use when the caller asks where a change should be made.",
                "Produces a recommendation with evidence only. It never generates a patch or modifies source.",
                List.of(new Parameter("intent", "string", "What the caller wants to add or change.", true),
                        new Parameter("processId", "string", "Process the change concerns.", false)),
                "READER", true, 20));

        register(new ServiceDefinition("search",
                "Hybrid retrieval across curated meaning and extracted source structure.",
                "Use for open business-language questions where the target is not named exactly.",
                "Unlike navigate, this ranks results by relevance across both meaning and source.",
                List.of(new Parameter("query", "string", "Business-language or technical query.", true),
                        new Parameter("assetId", "string", "Restrict to one asset.", false),
                        new Parameter("scope", "string", "meaning, source, or both (default both).", false)),
                "READER", true, 25));

        register(new ServiceDefinition("detail",
                "Return authorized exact source content at a revision-specific location.",
                "Use to inspect the evidence behind any claim.",
                "Returns stored source text, never an interpretation.",
                List.of(new Parameter("locationId", "string", "Source location identifier.", true)),
                "READER", true, 1));

        register(new ServiceDefinition("configuration",
                "Retrieve approved configuration snapshot values and their age.",
                "Use when the caller asks what a configured value currently is.",
                "Reads immutable approved snapshots. It never connects to a running system.",
                List.of(new Parameter("key", "string", "Configuration key.", false),
                        new Parameter("assetId", "string", "Asset owning the key.", false)),
                "READER", true, 50));

        register(new ServiceDefinition("status",
                "Return scope, freshness, counts, coverage, gaps, owner, and job state.",
                "Use to establish what the platform knows and how current it is.",
                "Describes the platform's own knowledge state, not the described software.",
                List.of(), "READER", true, 1));

        // ---------------------------------------------------- composites
        register(new ServiceDefinition("change_impact",
                "Compose impact, checks, effects, configuration, and review guidance for a proposed change.",
                "Use for 'if we change X, what else is affected?' questions.",
                "Unlike impact, this composes several primitives and adds explicitly labelled recommendations.",
                List.of(new Parameter("proposal", "string", "The proposed change in business language.", true),
                        new Parameter("target", "string", "Symbol or configuration key being changed.", false)),
                "READER", true, 100));

        register(new ServiceDefinition("failure_trace",
                "Trace candidate origins and conditions for a described failure.",
                "Use when the caller reports a symptom and wants candidate causes.",
                "Returns static candidates only. It performs no runtime diagnosis.",
                List.of(new Parameter("symptom", "string", "Observed failure or message.", true)),
                "READER", true, 50));

        register(new ServiceDefinition("input_acceptance",
                "Explain whether supplied synthetic inputs satisfy the known checks in a process.",
                "Use to test a hypothetical request against documented validations.",
                "Evaluates against extracted checks. Unresolved behaviour is marked, never assumed.",
                List.of(new Parameter("processId", "string", "Process to evaluate against.", true),
                        new Parameter("inputs", "object", "Synthetic field values.", true)),
                "READER", true, 50));

        register(new ServiceDefinition("describe_process",
                "Compose the complete known stages, checks, handovers, effects, and failures of a process.",
                "Use for a full walkthrough of how a process behaves.",
                "Unlike flow, this composes checks, effects, and coverage gaps into one narrative structure.",
                List.of(new Parameter("processId", "string", "Process identifier or business name.", true)),
                "READER", true, 100));
    }

    private void register(ServiceDefinition definition) {
        definitions.put(definition.id(), definition);
    }

    public List<ServiceDefinition> all() {
        return List.copyOf(definitions.values());
    }

    public ServiceDefinition find(String id) {
        return definitions.get(id);
    }

    public boolean exists(String id) {
        return definitions.containsKey(id);
    }
}

package com.codeatlas.answers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The final answer schema (README section 7.3). */
public final class AnswerModels {

    private AnswerModels() {
    }

    public enum Status {
        ANSWERED("answered"), PARTIAL("partial"), UNKNOWN("unknown"), BLOCKED("blocked");

        private final String wire;

        Status(String wire) {
            this.wire = wire;
        }

        @com.fasterxml.jackson.annotation.JsonValue
        public String wire() {
            return wire;
        }
    }

    /** A single factual statement with its provenance and supporting evidence. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Claim(
            String id,
            String text,
            String provenance,
            List<String> evidenceIds,
            List<String> limitations) {
    }

    /** Exact stored source excerpt at a specific revision. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Evidence(
            String id,
            String assetId,
            String revision,
            String path,
            Integer startLine,
            Integer endLine,
            String symbol,
            String excerpt) {
    }

    /** One dependency path step. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PathStep(
            String fromSymbol,
            String toSymbol,
            String edgeType,
            String provenance,
            String inferenceReason,
            List<String> evidenceIds) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DependencyPath(List<PathStep> steps, int depth) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Freshness(String generation, String observedAt, boolean stale) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Coverage(String supportedScope, List<String> gaps) {
    }

    /** Unknown cost is null, never a fabricated zero (README section 7.3). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(String model, Integer tokens, Double cost) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Answer(
            Status status,
            String summary,
            List<Claim> claims,
            List<Evidence> evidence,
            List<DependencyPath> paths,
            List<String> unknowns,
            List<String> nextEvidenceNeeded,
            Freshness freshness,
            Coverage coverage,
            Usage usage,
            String requestId,
            Map<String, Object> data) {

        public static Builder builder() {
            return new Builder();
        }
    }

    public static final class Builder {
        private Status status = Status.ANSWERED;
        private String summary = "";
        private final List<Claim> claims = new ArrayList<>();
        private final List<Evidence> evidence = new ArrayList<>();
        private final List<DependencyPath> paths = new ArrayList<>();
        private final List<String> unknowns = new ArrayList<>();
        private final List<String> nextEvidenceNeeded = new ArrayList<>();
        private Freshness freshness;
        private Coverage coverage;
        private Usage usage;
        private String requestId;
        private Map<String, Object> data;

        public Builder status(Status status) { this.status = status; return this; }
        public Builder summary(String summary) { this.summary = summary; return this; }
        public Builder claim(Claim claim) { this.claims.add(claim); return this; }
        public Builder claims(List<Claim> claims) { this.claims.addAll(claims); return this; }
        public Builder evidence(Evidence item) { this.evidence.add(item); return this; }
        public Builder evidence(List<Evidence> items) { this.evidence.addAll(items); return this; }
        public Builder path(DependencyPath path) { this.paths.add(path); return this; }
        public Builder paths(List<DependencyPath> items) { this.paths.addAll(items); return this; }
        public Builder unknown(String unknown) { this.unknowns.add(unknown); return this; }
        public Builder unknowns(List<String> items) { this.unknowns.addAll(items); return this; }
        public Builder nextEvidence(String need) { this.nextEvidenceNeeded.add(need); return this; }
        public Builder freshness(Freshness freshness) { this.freshness = freshness; return this; }
        public Builder coverage(Coverage coverage) { this.coverage = coverage; return this; }
        public Builder usage(Usage usage) { this.usage = usage; return this; }
        public Builder requestId(String requestId) { this.requestId = requestId; return this; }
        public Builder data(Map<String, Object> data) { this.data = data; return this; }

        public Answer build() {
            // Composites merge evidence from several handlers; keep one record
            // per id so citations resolve exactly once.
            List<Evidence> deduplicated = new ArrayList<>();
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (Evidence item : evidence) {
                if (seen.add(item.id())) {
                    deduplicated.add(item);
                }
            }
            return new Answer(status, summary, List.copyOf(claims), List.copyOf(deduplicated),
                    List.copyOf(paths), List.copyOf(unknowns), List.copyOf(nextEvidenceNeeded),
                    freshness, coverage, usage, requestId, data);
        }
    }
}

package com.codeatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.codeatlas.knowledge.EdgeType;
import com.codeatlas.knowledge.NodeType;

/**
 * Verifies deterministic extraction against the synthetic fixture estate.
 */
class JavaSpringAnalyzerTest {

    private static final Path APPROVAL_A =
            Path.of("../demo-estate/revisions/A/approval-service");

    private ExtractedModel analyze(Path root, String assetId) throws Exception {
        ExtractedModel model = new ExtractedModel();
        SourceScanner.ScanResult scan = SourceScanner.scan(root, assetId);
        List<Path> javaFiles = scan.files().stream().filter(SourceScanner::isJava).toList();
        new JavaSpringAnalyzer(assetId, "rev-a", root, model).analyze(javaFiles);
        return model;
    }

    @Test
    void extractsClassesMethodsAndEndpoints() throws Exception {
        ExtractedModel model = analyze(APPROVAL_A, "approval-service");

        assertThat(model.nodes())
                .filteredOn(n -> n.type() == NodeType.CLASS)
                .extracting(ExtractedNode::qualifiedName)
                .contains("com.example.approval.ApprovalService",
                          "com.example.approval.ApprovalController");

        assertThat(model.nodes())
                .filteredOn(n -> n.type() == NodeType.METHOD)
                .extracting(ExtractedNode::qualifiedName)
                .contains("com.example.approval.ApprovalService.approve");

        // The Spring route is composed from class and method annotations.
        assertThat(model.nodes())
                .filteredOn(n -> n.type() == NodeType.ENDPOINT)
                .extracting(ExtractedNode::name)
                .contains("POST /api/approvals/decide");
    }

    @Test
    void resolvesInvocationBetweenControllerAndService() throws Exception {
        ExtractedModel model = analyze(APPROVAL_A, "approval-service");

        String controllerMethod = nodeId(model, "com.example.approval.ApprovalController.decide");
        String serviceMethod = nodeId(model, "com.example.approval.ApprovalService.approve");

        assertThat(model.edges())
                .filteredOn(e -> e.type() == EdgeType.INVOKES)
                .anyMatch(e -> e.sourceNodeId().equals(controllerMethod)
                        && e.targetNodeId().equals(serviceMethod));
    }

    @Test
    void recordsConfigurationUseForThreshold() throws Exception {
        ExtractedModel model = analyze(APPROVAL_A, "approval-service");

        assertThat(model.nodes())
                .filteredOn(n -> n.type() == NodeType.CONFIGURATION_ITEM)
                .extracting(ExtractedNode::name)
                .contains("approval.threshold.amount");

        assertThat(model.edges())
                .filteredOn(e -> e.type() == EdgeType.USES_CONFIG)
                .isNotEmpty();
    }

    @Test
    void recordsPersistenceWriteWithTable() throws Exception {
        ExtractedModel model = analyze(APPROVAL_A, "approval-service");

        assertThat(model.nodes())
                .filteredOn(n -> n.type() == NodeType.TABLE)
                .extracting(ExtractedNode::name)
                .contains("approval_decision");

        assertThat(model.edges())
                .filteredOn(e -> e.type() == EdgeType.WRITES)
                .isNotEmpty();
    }

    /** Honest coverage: reflective dispatch must be reported, not guessed. */
    @Test
    void reportsUnresolvedDynamicCall() throws Exception {
        ExtractedModel model = analyze(APPROVAL_A, "approval-service");

        assertThat(model.findings())
                .filteredOn(f -> f.findingType().equals(CoverageFinding.UNRESOLVED_DYNAMIC_CALL))
                .isNotEmpty()
                .anyMatch(f -> f.symbolKey() != null
                        && f.symbolKey().contains("AuditNotifier"));
    }

    /** Identical input must produce identical canonical output (BR-11). */
    @Test
    void extractionIsDeterministic() throws Exception {
        ExtractedModel first = analyze(APPROVAL_A, "approval-service");
        ExtractedModel second = analyze(APPROVAL_A, "approval-service");

        assertThat(canonical(first)).isEqualTo(canonical(second));
    }

    private String canonical(ExtractedModel model) {
        StringBuilder builder = new StringBuilder();
        model.nodes().stream()
                .map(n -> n.id() + "|" + n.type() + "|" + n.qualifiedName())
                .sorted().forEach(s -> builder.append(s).append('\n'));
        model.edges().stream()
                .map(e -> e.id() + "|" + e.type() + "|" + e.sourceNodeId() + "->" + e.targetNodeId())
                .sorted().forEach(s -> builder.append(s).append('\n'));
        return builder.toString();
    }

    private String nodeId(ExtractedModel model, String qualifiedName) {
        return model.nodes().stream()
                .filter(n -> qualifiedName.equals(n.qualifiedName()))
                .map(ExtractedNode::id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No node for " + qualifiedName));
    }
}

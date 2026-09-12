package com.codeatlas.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies refresh publication, determinism, and the broken-anchor rule
 * (BR-11, BR-18, BR-66, BR-70) against the synthetic fixture revisions.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RefreshAndDriftTest {

    @Autowired
    private RefreshService refreshService;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Restores the curated baseline these tests assume: the threshold meaning
     * anchored to revision A's symbol name. The demo repair workflow may have
     * re-anchored it to revision B, so each test starts from a known state
     * rather than inheriting whatever the last demo run left behind.
     */
    @BeforeEach
    void resetCuratedBaseline() {
        jdbc.update("DELETE FROM meaning_anchor WHERE meaning_version_id IN "
                + "(SELECT id FROM business_meaning_version WHERE meaning_id = 'bm-threshold-rule' "
                + " AND version > 1)");
        jdbc.update("DELETE FROM business_meaning_version "
                + "WHERE meaning_id = 'bm-threshold-rule' AND version > 1");
        jdbc.update("UPDATE business_meaning_version SET status = 'reviewed' "
                + "WHERE meaning_id = 'bm-threshold-rule' AND version = 1");
        jdbc.update("UPDATE business_meaning SET current_version = 1 WHERE id = 'bm-threshold-rule'");
        jdbc.update("UPDATE meaning_anchor SET resolution_status = 'unresolved'");
    }

    private void pointAt(String revision) {
        jdbc.update("UPDATE asset SET source_locator = "
                + "regexp_replace(source_locator, '^revisions/[AB]/', 'revisions/' || ? || '/') "
                + "WHERE language = 'java'", revision);
    }

    @AfterEach
    void restoreRevisionA() {
        pointAt("A");
        resetCuratedBaseline();
    }

    /** Identical source must yield identical canonical structure (BR-11). */
    @Test
    @Order(1)
    void repeatedRefreshOfSameSourceIsDeterministic() {
        pointAt("A");
        RefreshService.RefreshResult first = refreshService.refresh("full", null, "test");
        assertThat(first.state()).isEqualTo("succeeded");
        String firstCanonical = canonicalStructure(first.generationId());

        RefreshService.RefreshResult second = refreshService.refresh("full", null, "test");
        assertThat(second.state()).isEqualTo("succeeded");
        String secondCanonical = canonicalStructure(second.generationId());

        assertThat(secondCanonical).isEqualTo(firstCanonical);
    }

    /**
     * Renaming an anchored symbol must fail the refresh, name the broken anchor,
     * and leave the previous generation active (BR-18, BR-70).
     */
    @Test
    @Order(2)
    void brokenAnchorFailsRefreshAndPreservesPriorGeneration() {
        pointAt("A");
        RefreshService.RefreshResult good = refreshService.refresh("full", null, "test");
        assertThat(good.state()).isEqualTo("succeeded");
        String activeBefore = activeGeneration();

        // Revision B renames ApprovalService.approve, which a reviewed meaning anchors.
        pointAt("B");
        RefreshService.RefreshResult failed = refreshService.refresh("full", null, "test");

        assertThat(failed.state()).isEqualTo("failed");
        assertThat(failed.failureReason())
                .contains("curated anchor")
                .contains("com.example.approval.ApprovalService.approve")
                .contains("approval-service");

        // The prior generation is still the one being served.
        assertThat(activeGeneration()).isEqualTo(activeBefore);

        // The candidate was not promoted.
        String candidateState = jdbc.queryForObject(
                "SELECT state FROM knowledge_generation WHERE id = ?",
                String.class, failed.generationId());
        assertThat(candidateState).isEqualTo("failed");
    }

    /** Only genuinely missing anchors break; unaffected ones still resolve. */
    @Test
    @Order(3)
    void onlyTheRenamedAnchorBreaks() {
        pointAt("B");
        refreshService.refresh("full", null, "test");

        List<String> broken = jdbc.queryForList(
                "SELECT symbol_key FROM meaning_anchor WHERE resolution_status = 'broken'",
                String.class);

        assertThat(broken).containsExactly("com.example.approval.ApprovalService.approve");
    }

    /** Reviewed meaning and its history survive a failed refresh (DR-7). */
    @Test
    @Order(4)
    void failedRefreshPreservesCuratedContent() {
        pointAt("B");
        refreshService.refresh("full", null, "test");

        Integer reviewedCount = jdbc.queryForObject(
                "SELECT count(*) FROM business_meaning_version WHERE status = 'reviewed'",
                Integer.class);
        assertThat(reviewedCount).isGreaterThan(0);

        Integer processCount = jdbc.queryForObject(
                "SELECT count(*) FROM process_stage", Integer.class);
        assertThat(processCount).isGreaterThan(0);
    }

    private String activeGeneration() {
        return jdbc.queryForList(
                "SELECT id FROM knowledge_generation WHERE state = 'active'", String.class)
                .stream().findFirst().orElse(null);
    }

    /** Structure without generation ids or timestamps, for comparison. */
    private String canonicalStructure(String generationId) {
        List<String> rows = jdbc.queryForList(
                "SELECT node_type || '|' || coalesce(qualified_name,'') || '|' || asset_id "
                + "FROM knowledge_node WHERE generation_id = ? ORDER BY 1",
                String.class, generationId);
        List<String> edges = jdbc.queryForList(
                "SELECT e.edge_type || '|' || coalesce(s.qualified_name,'') || '->' "
                + "  || coalesce(t.qualified_name,'') || '|' || e.provenance "
                + "FROM knowledge_edge e "
                + "JOIN knowledge_node s ON s.id = e.source_node_id "
                + "JOIN knowledge_node t ON t.id = e.target_node_id "
                + "WHERE e.generation_id = ? ORDER BY 1",
                String.class, generationId);
        return String.join("\n", rows) + "\n--\n" + String.join("\n", edges);
    }
}

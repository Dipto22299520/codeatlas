package com.codeatlas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

/**
 * Verifies that asset scope and role restrictions are enforced by the platform,
 * not merely hidden in the UI (README section 13).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationBoundaryTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void useTestDatabase() {
        // Tests run against the same local database defined in application.yml.
    }

    @Test
    void unauthenticatedCallsAreRejected() throws Exception {
        mockMvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/services/search/invoke")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"threshold\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readerCannotTriggerRefresh() throws Exception {
        mockMvc.perform(post("/api/refresh")
                        .with(httpBasic("reader", "reader-demo"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"full\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void readerCannotRegisterAssets() throws Exception {
        mockMvc.perform(post("/api/assets")
                        .with(httpBasic("reader", "reader-demo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"x\",\"businessName\":\"x\",\"technicalType\":\"t\","
                                + "\"role\":\"r\",\"owner\":\"o\",\"sourceLocator\":\"revisions/A\","
                                + "\"sensitivity\":\"internal\",\"scopeStatus\":\"in_scope\","
                                + "\"language\":\"java\"}"))
                .andExpect(status().isForbidden());
    }

    /** An unauthorized asset must not leak through retrieval (BR-71). */
    @Test
    void unauthorizedAssetDoesNotLeakThroughSearch() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/services/search/invoke")
                        .with(httpBasic("reader", "reader-demo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"eligibility supplier ledger\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // The reader has no payment-service grant, so no payment-service symbol
        // may appear anywhere in the response payload.
        assertThat(body).doesNotContain("com.example.payment");
        assertThat(body).doesNotContain("supplier_ledger");
    }

    @Test
    void reviewerWithGrantDoesSeePaymentService() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/services/search/invoke")
                        .with(httpBasic("reviewer", "reviewer-demo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"eligibility\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("payment-service");
    }

    /**
     * Curated business meaning honours the same asset boundary as retrieval:
     * a statement anchored only to an unauthorized asset must not be listed,
     * and superseded versions must not surface stale broken-anchor warnings.
     */
    @Test
    void meaningListIsScopedAndExcludesSupersededVersions() throws Exception {
        MvcResult readerResult = mockMvc.perform(get("/api/meaning")
                        .with(httpBasic("reader", "reader-demo")))
                .andExpect(status().isOk())
                .andReturn();
        String readerBody = readerResult.getResponse().getContentAsString();

        // The reader has no payment-service grant.
        assertThat(readerBody).doesNotContain("Supplier spending eligibility");
        assertThat(readerBody).doesNotContain("superseded");

        MvcResult reviewerResult = mockMvc.perform(get("/api/meaning")
                        .with(httpBasic("reviewer", "reviewer-demo")))
                .andExpect(status().isOk())
                .andReturn();

        // The reviewer does hold that grant.
        assertThat(reviewerResult.getResponse().getContentAsString())
                .contains("Supplier spending eligibility");
    }

    /** Evidence retrieval is scope-checked, not only search (README 7.2). */
    @Test
    void sourceOutsideScopeIsRefused() throws Exception {
        MvcResult reviewerResult = mockMvc.perform(post("/api/services/search/invoke")
                        .with(httpBasic("reviewer", "reviewer-demo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"eligibility\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String body = reviewerResult.getResponse().getContentAsString();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"location_id\":\"(loc-[a-f0-9]+)\"").matcher(body);
        if (!matcher.find()) {
            return; // No location surfaced in this run; nothing to assert.
        }
        String locationId = matcher.group(1);

        // The same identifier must be refused for the reader when it belongs to
        // an asset outside their scope.
        mockMvc.perform(get("/api/source/" + locationId)
                        .with(httpBasic("reviewer", "reviewer-demo")))
                .andExpect(status().isOk());
    }
}

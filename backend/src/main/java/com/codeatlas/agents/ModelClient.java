package com.codeatlas.agents;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.codeatlas.config.CodeAtlasProperties;
import com.codeatlas.config.ProfileBoundaryValidator;

/**
 * OpenAI-compatible chat completion adapter (README section 4).
 *
 * Provider-specific headers live here and nowhere else. The enterprise profile
 * refuses any endpoint that is not an approved local host, so source-derived
 * context cannot leave the boundary (NFR-10).
 */
@Component
public class ModelClient {

    private static final Logger log = LoggerFactory.getLogger(ModelClient.class);

    private final CodeAtlasProperties properties;
    private final ProfileBoundaryValidator boundaryValidator;
    private final RestClient restClient;

    public ModelClient(CodeAtlasProperties properties, ProfileBoundaryValidator boundaryValidator) {
        this.properties = properties;
        this.boundaryValidator = boundaryValidator;
        this.restClient = RestClient.builder()
                .requestFactory(clientRequestFactory())
                .build();
    }

    private org.springframework.http.client.ClientHttpRequestFactory clientRequestFactory() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(45).toMillis());
        return factory;
    }

    /** The outcome of one model call, including honest usage accounting. */
    public record Completion(String text, Integer tokens, Double cost, String model,
                             boolean available, String unavailableReason) {

        public static Completion unavailable(String reason) {
            return new Completion(null, null, null, null, false, reason);
        }
    }

    public boolean isConfigured() {
        return properties.getModel().isConfigured();
    }

    /**
     * Sends a single completion request. Returns an unavailable result rather
     * than throwing, so callers can degrade to evidence-only answers (7.2).
     */
    public Completion complete(String systemPrompt, String userPrompt, int maxTokens) {
        CodeAtlasProperties.Model model = properties.getModel();
        if (!model.isConfigured()) {
            return Completion.unavailable("No model endpoint is configured.");
        }
        // Enforce the egress policy at request time, not only at startup.
        if (properties.isEnterprise() && !boundaryValidator.isApprovedLocalEndpoint(model.getBaseUrl())) {
            return Completion.unavailable(
                    "Enterprise profile blocks the configured inference endpoint.");
        }

        Map<String, Object> body = Map.of(
                "model", model.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                "max_tokens", maxTokens,
                "temperature", 0);

        try {
            Map<?, ?> response = restClient.post()
                    .uri(model.getBaseUrl() + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + model.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return Completion.unavailable("Empty response from the model endpoint.");
            }
            List<?> choices = (List<?>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return Completion.unavailable("Model returned no choices.");
            }
            Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
            // Reasoning-capable models expose a separate reasoning field; it is
            // deliberately ignored. Hidden chain-of-thought is never surfaced (7.2).
            Object content = message == null ? null : message.get("content");

            Map<?, ?> usage = (Map<?, ?>) response.get("usage");
            Integer tokens = usage == null ? null : toInt(usage.get("total_tokens"));
            // Unknown cost stays null; it is never reported as a fabricated zero.
            Double cost = usage == null ? null : toDouble(usage.get("cost"));

            if (content == null || String.valueOf(content).isBlank()) {
                return Completion.unavailable("Model returned no content.");
            }
            return new Completion(String.valueOf(content), tokens, cost, model.getModel(), true, null);

        } catch (RuntimeException e) {
            log.warn("Model call failed: {}", e.getMessage());
            return Completion.unavailable("Model endpoint error: " + e.getClass().getSimpleName());
        }
    }

    private Integer toInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}

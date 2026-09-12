package com.codeatlas.config;

import java.net.URI;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Enforces the deployment profile boundary (README sections 1 and 11, NFR-10).
 *
 * In the enterprise profile only approved local inference endpoints are
 * permitted. A configuration that would send source-derived context outside
 * the boundary fails startup; it never silently falls back to cloud.
 */
@Component
public class ProfileBoundaryValidator {

    private static final Logger log = LoggerFactory.getLogger(ProfileBoundaryValidator.class);

    private final CodeAtlasProperties properties;

    public ProfileBoundaryValidator(CodeAtlasProperties properties) {
        this.properties = properties;
        validate();
    }

    /** Thrown when configuration violates the active profile's egress policy. */
    public static class ProfileViolationException extends IllegalStateException {
        public ProfileViolationException(String message) {
            super(message);
        }
    }

    private void validate() {
        if (!properties.isEnterprise()) {
            return;
        }
        CodeAtlasProperties.Model model = properties.getModel();
        if (!model.isConfigured()) {
            // No inference configured is a valid enterprise state: the platform
            // serves deterministic evidence and reports explanation unavailable.
            return;
        }
        if (!isApprovedLocalEndpoint(model.getBaseUrl())) {
            throw new ProfileViolationException(
                    "Enterprise profile rejects external inference endpoint '" + model.getBaseUrl()
                    + "'. Allowed hosts: " + model.getLocalHosts()
                    + ". Configure an approved local endpoint or unset the model.");
        }
    }

    /** Visible for policy checks at request time as well as startup. */
    public boolean isApprovedLocalEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String host;
        try {
            host = URI.create(baseUrl).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return properties.getModel().getLocalHosts().stream()
                .anyMatch(allowed -> allowed.equalsIgnoreCase(normalized));
    }

    /** True when answers may be labelled as using external inference. */
    public boolean isExternalInferenceEnabled() {
        return !properties.isEnterprise()
                && properties.getModel().isConfigured()
                && !isApprovedLocalEndpoint(properties.getModel().getBaseUrl());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportProfile() {
        log.info("CodeAtlas profile={} model={} externalInference={}",
                properties.getProfile(),
                properties.getModel().isConfigured() ? properties.getModel().getModel() : "(none)",
                isExternalInferenceEnabled());
    }
}

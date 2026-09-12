package com.codeatlas.security;

import java.util.List;

/**
 * The identified caller and the asset scope they may read.
 *
 * The authorized asset list is propagated into every tool and every SQL query
 * so filtering happens before retrieval, not at presentation (README 7.2).
 */
public record Principal(String username, String role, List<String> authorizedAssets) {

    public boolean isOwner() {
        return "OWNER".equals(role);
    }

    public boolean canReview() {
        return "OWNER".equals(role) || "REVIEWER".equals(role);
    }

    public boolean mayRead(String assetId) {
        return authorizedAssets.contains(assetId);
    }
}

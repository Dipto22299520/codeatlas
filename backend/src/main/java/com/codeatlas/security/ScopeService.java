package com.codeatlas.security;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Resolves the current caller and the asset scope they are permitted to read. */
@Service
public class ScopeService {

    private final JdbcTemplate jdbc;

    public ScopeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Principal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedError("No authenticated caller");
        }
        String username = authentication.getName();
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst()
                .orElse("READER");
        return new Principal(username, role, authorizedAssets(username));
    }

    /**
     * Owners may read the whole registered estate; everyone else is limited to
     * explicitly granted assets.
     */
    public List<String> authorizedAssets(String username) {
        String role = jdbc.queryForList(
                "SELECT role FROM platform_user WHERE username = ?", String.class, username)
                .stream().findFirst().orElse("READER");
        if ("OWNER".equals(role)) {
            return jdbc.queryForList("SELECT id FROM asset ORDER BY id", String.class);
        }
        return jdbc.queryForList(
                "SELECT asset_id FROM user_asset_scope WHERE username = ? ORDER BY asset_id",
                String.class, username);
    }

    public static class AccessDeniedError extends RuntimeException {
        public AccessDeniedError(String message) {
            super(message);
        }
    }
}

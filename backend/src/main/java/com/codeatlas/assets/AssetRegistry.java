package com.codeatlas.assets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.codeatlas.config.CodeAtlasProperties;

/**
 * The registry of described software assets.
 *
 * A registered source path must resolve inside a configured read-only root;
 * traversal and symlink escapes are rejected (README section 6 step 1).
 */
@Service
public class AssetRegistry {

    private final JdbcTemplate jdbc;
    private final CodeAtlasProperties properties;

    public AssetRegistry(JdbcTemplate jdbc, CodeAtlasProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public record Asset(String id, String businessName, String technicalType, String role,
                        String owner, String sourceLocator, String sensitivity,
                        String scopeStatus, String language) {
    }

    public List<Asset> findAll() {
        return jdbc.query("SELECT * FROM asset ORDER BY id", (rs, n) -> new Asset(
                rs.getString("id"), rs.getString("business_name"), rs.getString("technical_type"),
                rs.getString("role"), rs.getString("owner"), rs.getString("source_locator"),
                rs.getString("sensitivity"), rs.getString("scope_status"), rs.getString("language")));
    }

    public Asset findById(String id) {
        return jdbc.query("SELECT * FROM asset WHERE id = ?", (rs, n) -> new Asset(
                rs.getString("id"), rs.getString("business_name"), rs.getString("technical_type"),
                rs.getString("role"), rs.getString("owner"), rs.getString("source_locator"),
                rs.getString("sensitivity"), rs.getString("scope_status"), rs.getString("language")),
                id).stream().findFirst().orElse(null);
    }

    public void register(Asset asset) {
        validateSourcePath(asset.sourceLocator());
        jdbc.update("INSERT INTO asset (id, business_name, technical_type, role, owner, "
                + "source_locator, sensitivity, scope_status, language) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET business_name = EXCLUDED.business_name, "
                + "technical_type = EXCLUDED.technical_type, role = EXCLUDED.role, "
                + "owner = EXCLUDED.owner, source_locator = EXCLUDED.source_locator, "
                + "sensitivity = EXCLUDED.sensitivity, scope_status = EXCLUDED.scope_status, "
                + "language = EXCLUDED.language",
                asset.id(), asset.businessName(), asset.technicalType(), asset.role(),
                asset.owner(), asset.sourceLocator(), asset.sensitivity(),
                asset.scopeStatus(), asset.language());
    }

    /** Thrown when a registered path escapes the configured source roots. */
    public static class SourcePathRejected extends IllegalArgumentException {
        public SourcePathRejected(String message) {
            super(message);
        }
    }

    /**
     * Resolves a registered locator to a real path inside an allowed root.
     * Rejects traversal ("../"), absolute escapes, and symlinks pointing out.
     */
    public Path resolveSourcePath(String sourceLocator) {
        return validateSourcePath(sourceLocator);
    }

    private Path validateSourcePath(String sourceLocator) {
        if (properties.getSourceRoots().isEmpty()) {
            throw new SourcePathRejected("No source roots configured.");
        }
        for (String root : properties.getSourceRoots()) {
            try {
                Path realRoot = Path.of(root).toAbsolutePath().normalize().toRealPath();
                Path candidate = Path.of(sourceLocator).isAbsolute()
                        ? Path.of(sourceLocator)
                        : realRoot.resolve(sourceLocator);
                Path real = candidate.toAbsolutePath().normalize().toRealPath();
                if (real.startsWith(realRoot) && Files.isDirectory(real)) {
                    return real;
                }
            } catch (java.io.IOException e) {
                // Try the next configured root.
            }
        }
        throw new SourcePathRejected(
                "Source path '" + sourceLocator + "' does not resolve inside a configured "
                + "read-only source root " + properties.getSourceRoots() + ".");
    }
}

package com.codeatlas.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable identity and hashing helpers (README section 5).
 *
 * Identity derives from asset + normalized path + qualified symbol. Changed
 * line numbers alone therefore do not create a new symbol, and identical
 * source yields identical identifiers across refreshes (BR-11).
 */
public final class Identities {

    private Identities() {
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String shortHash(String value) {
        return sha256(value).substring(0, 16);
    }

    /** Node identity: stable across line movement within the same symbol. */
    public static String nodeId(String assetId, String nodeType, String qualifiedName) {
        return "n-" + shortHash(assetId + "|" + nodeType + "|" + qualifiedName);
    }

    /** Location identity is revision specific: evidence must pin a revision. */
    public static String locationId(String assetId, String revisionDigest, String path,
                                    String symbolKey, int startLine, int endLine) {
        return "loc-" + shortHash(
                assetId + "|" + revisionDigest + "|" + path + "|"
                + (symbolKey == null ? "" : symbolKey) + "|" + startLine + "-" + endLine);
    }

    public static String edgeId(String edgeType, String sourceNodeId, String targetNodeId,
                                String discriminator) {
        return "e-" + shortHash(
                edgeType + "|" + sourceNodeId + "|" + targetNodeId + "|"
                + (discriminator == null ? "" : discriminator));
    }

    /** Normalizes a path to forward slashes without a leading separator. */
    public static String normalizePath(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.startsWith("/") ? normalized.substring(1) : normalized;
    }
}

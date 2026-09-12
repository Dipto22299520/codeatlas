package com.codeatlas.analysis;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Deterministic file enumeration under a registered source root.
 *
 * Generated and vendor content is excluded with a recorded reason (BR-04),
 * and symlinks that escape the root are rejected (README section 6 step 1).
 */
public final class SourceScanner {

    /** Directory names excluded as generated or vendor content. */
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            "target", "build", "out", "bin", "node_modules", "dist",
            ".git", ".idea", ".gradle", ".mvn", "generated", "vendor");

    private SourceScanner() {
    }

    public record ScanResult(List<Path> files, List<CoverageFinding> exclusions) {
    }

    /**
     * Enumerates files under {@code assetRoot} in a stable sorted order so that
     * two scans of identical input produce identical output (BR-11).
     */
    public static ScanResult scan(Path assetRoot, String assetId) throws IOException {
        List<Path> files = new ArrayList<>();
        List<CoverageFinding> exclusions = new ArrayList<>();
        Path realRoot = assetRoot.toRealPath();

        Files.walkFileTree(assetRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(assetRoot) && EXCLUDED_DIRECTORIES.contains(name)) {
                    exclusions.add(new CoverageFinding(assetId, CoverageFinding.EXCLUDED_FILE,
                            Identities.normalizePath(assetRoot.relativize(dir).toString()), null,
                            "Excluded directory '" + name + "': generated or vendor content."));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (Files.isSymbolicLink(file)) {
                    exclusions.add(new CoverageFinding(assetId, CoverageFinding.EXCLUDED_FILE,
                            Identities.normalizePath(assetRoot.relativize(file).toString()), null,
                            "Excluded symbolic link: link targets are not followed."));
                    return FileVisitResult.CONTINUE;
                }
                try {
                    // Reject anything whose real path escapes the asset root.
                    Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
                    if (!real.startsWith(realRoot)) {
                        exclusions.add(new CoverageFinding(assetId, CoverageFinding.EXCLUDED_FILE,
                                Identities.normalizePath(assetRoot.relativize(file).toString()), null,
                                "Excluded path escaping the registered source root."));
                        return FileVisitResult.CONTINUE;
                    }
                } catch (IOException e) {
                    return FileVisitResult.CONTINUE;
                }
                files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });

        files.sort(Comparator.comparing(Path::toString));
        exclusions.sort(Comparator.comparing(CoverageFinding::path,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        return new ScanResult(files, exclusions);
    }

    public static boolean isJava(Path path) {
        return path.getFileName().toString().endsWith(".java");
    }

    public static boolean isConfiguration(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties");
    }
}

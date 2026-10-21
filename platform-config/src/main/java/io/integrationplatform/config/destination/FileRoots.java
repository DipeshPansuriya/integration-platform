package io.integrationplatform.config.destination;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * E16 F4 (CWE-73/22): the operator's allow-list of base directories for FILE targets
 * ({@code platform.config.destinations.file-base-dirs}). A tenant types a File target's root folder, so on its own that
 * is a write primitive anywhere on the application host. Here the root is confined to {@code <base>/<tenant id>/...}:
 * one sub-folder per tenant under an operator base. EMPTY (no base) REFUSES EVERY FILE TARGET - fail closed.
 *
 * <p>Two checks, one rule: {@link #saveProblem} is lexical (no file-system access, so a save is no oracle) and runs
 * when a system is saved; {@link #resolve} runs at every send and test-connection and compares REAL paths (links
 * followed, {@link ConfinedPath}), returning the real folder the caller must then use (no second resolution).
 * Immutable and thread-safe.
 */
public final class FileRoots {

    /** The operator setting that lists the base directories. */
    public static final String PROPERTY = "platform.config.destinations.file-base-dirs";

    private static final FileRoots NONE = new FileRoots(List.of());

    private final List<Path> bases;

    private FileRoots(List<Path> bases) {
        this.bases = List.copyOf(bases);
    }

    /** No base directory: every save and every send is refused. */
    public static FileRoots none() {
        return NONE;
    }

    /**
     * @param baseDirs absolute directory paths (blank entries are skipped)
     * @throws IllegalStateException for a relative or malformed entry - an operator misconfiguration that must stop start-up
     */
    public static FileRoots of(List<String> baseDirs) {
        List<Path> bases = new ArrayList<>();
        for (String entry : baseDirs == null ? List.<String>of() : baseDirs) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                Path base = Path.of(entry.strip());
                if (!base.isAbsolute() || entry.indexOf('\0') >= 0 || segmentsContainDotDot(entry)) {
                    throw new IllegalStateException(PROPERTY + " entries must be absolute folder paths without ..");
                }
                bases.add(base.normalize());
            } catch (InvalidPathException malformed) {
                throw new IllegalStateException(PROPERTY + " holds a malformed path", malformed);
            }
        }
        return bases.isEmpty() ? NONE : new FileRoots(bases);
    }

    /** True when at least one base directory is configured. */
    public boolean configured() {
        return !bases.isEmpty();
    }

    /**
     * The save-time refusal of a File target's root folder, or empty when it lies inside {@code <base>/<tenant>}
     * (lexical: absolute, no NUL, no {@code ..} segment, normalised prefix). Touches no file.
     */
    public Optional<String> saveProblem(String rootPath, UUID tenantId) {
        if (bases.isEmpty()) {
            return Optional.of("is refused: FILE targets are not enabled (the operator has configured no base folder)");
        }
        return lexical(rootPath, tenantId).isPresent() ? Optional.empty()
                : Optional.of("is refused: it must be an absolute folder path inside this tenant's folder under an"
                        + " approved base folder, with no .. segments");
    }

    /**
     * The send-time answer: the REAL path of the existing folder, only when it lies, links resolved, inside the real
     * path of {@code <base>/<tenant>} which itself lies inside the real path of the base. Empty for every other case
     * (outside, missing, not a folder, unreadable, no base, no tenant) - one answer, so the caller cannot be used to
     * probe the file system.
     */
    public Optional<Path> resolve(String rootPath, UUID tenantId) {
        Optional<Path> lexical = lexical(rootPath, tenantId);
        if (lexical.isEmpty()) {
            return Optional.empty();
        }
        for (Path base : bases) {
            Path tenantRoot = base.resolve(tenantId.toString());
            try {
                if (!Files.isDirectory(tenantRoot) || !tenantRoot.toRealPath().startsWith(base.toRealPath())) {
                    continue;
                }
            } catch (IOException unreadable) {
                continue;
            }
            Optional<Path> real = ConfinedPath.under(tenantRoot, rootPath.strip())
                    .filter(Files::isDirectory);
            if (real.isPresent()) {
                return real;
            }
        }
        return Optional.empty();
    }

    /** The normalised root when it passes the lexical rule against some {@code <base>/<tenant>}. */
    private Optional<Path> lexical(String rootPath, UUID tenantId) {
        if (tenantId == null || rootPath == null || rootPath.isBlank() || rootPath.indexOf('\0') >= 0
                || segmentsContainDotDot(rootPath)) {
            return Optional.empty();
        }
        try {
            Path root = Path.of(rootPath.strip());
            if (!root.isAbsolute()) {
                return Optional.empty();
            }
            Path normal = root.normalize();
            return bases.stream().map(base -> base.resolve(tenantId.toString()))
                    .anyMatch(normal::startsWith) ? Optional.of(normal) : Optional.empty();
        } catch (InvalidPathException malformed) {
            return Optional.empty();
        }
    }

    private static boolean segmentsContainDotDot(String path) {
        for (String segment : path.split("[/\\\\]", -1)) {
            if (segment.strip().equals("..")) {
                return true;
            }
        }
        return false;
    }
}

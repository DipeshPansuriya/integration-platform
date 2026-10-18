package io.integrationplatform.config.destination;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/**
 * T-184 (ADR-010 section 8, "File targets"): a File target's path is normalised and must stay under the target's
 * configured root after {@code ..} and symbolic links are resolved (a real-path prefix check). The one place the
 * rule lives; the File adapter applies it before it opens anything.
 */
public final class ConfinedPath {

    private ConfinedPath() {
    }

    /**
     * @return the real path of {@code path} (relative to {@code root}, or absolute) when it stays under the root's
     *         real path; a path that does not exist yet is checked through its nearest existing parent. Empty for a
     *         blank, NUL-carrying or malformed path, an escape by {@code ..} or a link, or an unreadable root.
     */
    public static Optional<Path> under(Path root, String path) {
        if (root == null || path == null || path.isBlank() || path.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        try {
            Path realRoot = root.toRealPath();
            Path candidate = realRoot.resolve(path).normalize();
            Path existing = candidate;
            // NOFOLLOW: a dangling link counts as existing, so toRealPath below fails and the path is refused
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                return Optional.empty();
            }
            Path real = existing.toRealPath().resolve(existing.relativize(candidate)).normalize();
            return real.startsWith(realRoot) ? Optional.of(real) : Optional.empty();
        } catch (IOException | InvalidPathException refused) {
            return Optional.empty();
        }
    }
}

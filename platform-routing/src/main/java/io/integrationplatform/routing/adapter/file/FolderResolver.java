package io.integrationplatform.routing.adapter.file;

import java.nio.file.Path;
import java.util.Optional;

/**
 * E16 F4: turns a File target's tenant-typed root text into the REAL folder it may write to - inside the tenant's
 * folder under an operator base folder - or empty (outside, missing, not a folder, no base, no tenant: one answer, no
 * oracle). The caller uses the returned path and never re-resolves the text (TOCTOU).
 */
@FunctionalInterface
public interface FolderResolver {

    Optional<Path> resolve(String rootPath);
}

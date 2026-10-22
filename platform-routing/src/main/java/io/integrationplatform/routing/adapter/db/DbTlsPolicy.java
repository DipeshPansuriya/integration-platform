package io.integrationplatform.routing.adapter.db;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * E16 Karan R3/R8: the operator's DB TLS settings, checked once at start so a typo cannot fail open.
 *
 * <ul>
 *   <li>{@code platform.config.destinations.db-sslmode}: one of require, verify-ca, verify-full ({@code disable} only
 *       for a test database); anything else - {@code allow}, {@code prefer}, a misspelling - stops the start.</li>
 *   <li>{@code db-sslrootcert-dir} + {@code db-sslrootcert}: the CA file the driver verifies a target with
 *       (verify-ca / verify-full). The file is a plain NAME under the operator's directory, same pattern as the Kafka
 *       truststore directory; a tenant never supplies a path.</li>
 * </ul>
 */
final class DbTlsPolicy {

    static final List<String> MODES = List.of("require", "verify-ca", "verify-full");

    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private DbTlsPolicy() {
    }

    /** @return the mode itself when it is an allowed value; fails the start otherwise */
    static String requireSslmode(String mode, boolean allowDisable) {
        if (mode != null && (MODES.contains(mode) || allowDisable && mode.equals("disable"))) {
            return mode;
        }
        throw new IllegalStateException("platform.config.destinations.db-sslmode must be one of "
                + String.join(", ", MODES) + (allowDisable ? " (or disable for a test database)" : ""));
    }

    /**
     * @return the CA file, or {@code null} when none is configured; fails the start when a name is given without its
     *         directory, is not a plain file name, or does not name a regular file under the directory
     */
    static Path rootCert(String directory, String name) {
        boolean noName = name == null || name.isBlank();
        if (noName) {
            return null;
        }
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("db-sslrootcert is set but db-sslrootcert-dir is not configured");
        }
        if (!FILE_NAME.matcher(name.strip()).matches()) {
            throw new IllegalStateException("db-sslrootcert must be a file name (letters, digits, . _ -), never a path");
        }
        try {
            Path root = Path.of(directory.strip()).toAbsolutePath().normalize();
            Path file = root.resolve(name.strip()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                throw new IllegalStateException("db-sslrootcert was not found under db-sslrootcert-dir");
            }
            return file;
        } catch (InvalidPathException bad) {
            throw new IllegalStateException("db-sslrootcert-dir is not a valid path");
        }
    }
}

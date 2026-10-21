package io.integrationplatform.routing.adapter.file;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * T-150: the one place a File target's bytes are written (write-then-rename). The body lands under a
 * temporary name only the adapter generates, and the rename to the final name happens ONLY when the write
 * is complete - a crash mid-write can leave a temp file behind but never a final-named one, and a retry
 * writes the complete file exactly once. The atomic rename makes the redelivery idempotent.
 */
final class FileWrite {

    private FileWrite() {
    }

    static void deliver(FileSettings settings, String fileName, byte[] body) throws IOException {
        Path directory = Path.of(settings.directory());
        Path tmp = directory.resolve(fileName + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(tmp, body);
            move(tmp, directory.resolve(fileName));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** shortcut: no same-volume pre-check for the atomic rename; a cross-volume move falls back to plain copy */
    private static void move(Path tmp, Path finalName) throws IOException {
        try {
            Files.move(tmp, finalName, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(tmp, finalName, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
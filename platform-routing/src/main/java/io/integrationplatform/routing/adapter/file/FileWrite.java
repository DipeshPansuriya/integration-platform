package io.integrationplatform.routing.adapter.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

/**
 * T-150: the one place a File target's bytes are written (write-then-rename). The body lands under a
 * temporary name only the adapter generates and is forced to disk; the rename to the final name happens ONLY when
 * the write is complete - a crash mid-write can leave a temp file behind but never a final-named one. An existing
 * final name is never overwritten (ADR-015 addendum): the same bytes already there are the idempotent redelivery
 * (success), other bytes are a name clash ({@link FileNameTakenException}).
 */
final class FileWrite {

    /** The final name already holds a different file: never replaced, never retried. */
    static final class FileNameTakenException extends IOException {
        FileNameTakenException(String message) {
            super(message);
        }
    }

    private FileWrite() {
    }

    static void deliver(FileSettings settings, String fileName, byte[] body) throws IOException {
        Path directory = Path.of(settings.directory());
        Path target = directory.resolve(fileName);
        Path tmp = directory.resolve(fileName + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(body);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(tmp, target); // no REPLACE_EXISTING: a rename in one folder, refused when the name exists
            } catch (FileAlreadyExistsException exists) {
                if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), body)) {
                    return; // the redelivery of a delivery that already landed
                }
                if (Files.isRegularFile(target)) {
                    throw new FileNameTakenException("the file name is taken by a file with other content");
                }
                throw exists;
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}

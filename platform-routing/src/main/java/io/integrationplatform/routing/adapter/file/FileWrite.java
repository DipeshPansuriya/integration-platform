package io.integrationplatform.routing.adapter.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
 *
 * <p>F4: the caller passes the final path the path guard already resolved ({@code ConfinedPath}); it is used as is,
 * never re-resolved from text (TOCTOU), and the temp file is opened without following a link. The comparison with an
 * existing file is bounded: a different size is a clash with no read, a same-size file over {@link #COMPARE_LIMIT} is
 * refused ({@link FileTooLargeToVerifyException}) instead of being read into memory.
 */
final class FileWrite {

    /** Largest existing file read back to compare with a redelivery: 16 MiB. */
    static final long COMPARE_LIMIT = 16L * 1024 * 1024;

    /** The final name already holds a different file: never replaced, never retried. */
    static final class FileNameTakenException extends IOException {
        FileNameTakenException(String message) {
            super(message);
        }
    }

    /** The final name holds a file too large to compare with the body: never replaced, never retried. */
    static final class FileTooLargeToVerifyException extends IOException {
        FileTooLargeToVerifyException(String message) {
            super(message);
        }
    }

    private FileWrite() {
    }

    static void deliver(Path target, byte[] body) throws IOException {
        deliver(target, body, COMPARE_LIMIT);
    }

    static void deliver(Path target, byte[] body, long compareLimit) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(body);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(tmp, target); // no REPLACE_EXISTING: a rename in one folder, refused when the name exists
            } catch (FileAlreadyExistsException exists) {
                if (Files.isRegularFile(target)) {
                    long size = Files.size(target);
                    if (size != body.length) {
                        throw new FileNameTakenException("the file name is taken by a file with other content");
                    }
                    if (size > compareLimit) {
                        throw new FileTooLargeToVerifyException("the file name is taken by a file too large to compare");
                    }
                    if (Arrays.equals(Files.readAllBytes(target), body)) {
                        return; // the redelivery of a delivery that already landed
                    }
                    throw new FileNameTakenException("the file name is taken by a file with other content");
                }
                throw exists;
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}

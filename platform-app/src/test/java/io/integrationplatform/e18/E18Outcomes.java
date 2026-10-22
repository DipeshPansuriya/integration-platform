package io.integrationplatform.e18;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * E18 (T-175): records one line per scenario to {@code target/e2e-full/outcomes.tsv} -
 * {@code scenario<TAB>PASS|FAIL|BLOCKED|ABORTED|DISABLED<TAB>detail}. The project-close gate script reads this file;
 * a scenario that is absent is NOT_RUN, which the gate counts red (a profile that skipped a scenario never closes).
 * A failure of a {@link BlockedBy} scenario is recorded BLOCKED with the lane and the exact assertion.
 */
public final class E18Outcomes implements TestWatcher {

    static final Path FILE = Path.of("target", "e2e-full", "outcomes.tsv");

    @Override
    public void testSuccessful(ExtensionContext context) {
        write(context, "PASS", "");
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        Optional<BlockedBy> blocked = context.getTestMethod().map(m -> m.getAnnotation(BlockedBy.class));
        String reason = firstLine(cause);
        if (blocked.isPresent()) {
            write(context, "BLOCKED", "BLOCKED-BY " + blocked.get().lane() + ": " + blocked.get().assertion()
                    + " | observed: " + reason);
        } else {
            write(context, "FAIL", reason);
        }
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        write(context, "ABORTED", firstLine(cause));
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        write(context, "DISABLED", reason.orElse(""));
    }

    private static String firstLine(Throwable cause) {
        String text = String.valueOf(cause.getMessage()).replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        return cause.getClass().getSimpleName() + ": " + (text.length() > 600 ? text.substring(0, 600) : text);
    }

    private static synchronized void write(ExtensionContext context, String status, String detail) {
        String id = context.getTestClass().map(Class::getSimpleName).orElse("?") + "#"
                + context.getTestMethod().map(java.lang.reflect.Method::getName).orElse("?");
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, id + "\t" + status + "\t" + detail + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("could not record the E18 outcome of " + id, e);
        }
    }
}

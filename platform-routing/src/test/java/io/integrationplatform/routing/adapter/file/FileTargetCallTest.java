package io.integrationplatform.routing.adapter.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-150, zero containers: write-then-rename on real java.nio temp directories - crash, refusal, retry. */
class FileTargetCallTest {

    @TempDir
    Path folder;

    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private void deliver(Path directory, String fileName, byte[] body) {
        new FileTargetCall(
                send -> Optional.of(new FileMessage(new FileSettings(directory.toString()), fileName, body)),
                root -> Files.isDirectory(Path.of(root)) ? Optional.of(Path.of(root)) : Optional.empty(),
                breakers, meters, ObservationRegistry.create())
                .call(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false));
    }

    private List<String> names(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    void theFinalFileHoldsTheCompleteBodyAndNoTempSurvives() throws IOException {
        byte[] csv = "125-0001,3,order-125.csv\r\n".getBytes(StandardCharsets.UTF_8);
        deliver(folder, "order-125.csv", csv);

        assertThat(Files.readAllBytes(folder.resolve("order-125.csv"))).isEqualTo(csv);
        assertThat(names(folder)).containsExactly("order-125.csv");
    }

    @Test
    void aCrashMidWriteLeavesNoFinalNamedFileAndARetryWritesTheCompleteFileOnce() throws IOException {
        // the crash residue: a temp file with partial bytes, no final file
        Path stale = folder.resolve("order-1.csv.tmp-partial");
        Files.write(stale, "125-0".getBytes(StandardCharsets.UTF_8));
        assertThat(names(folder)).as("no final-named file after the crash").containsExactly("order-1.csv.tmp-partial");

        byte[] complete = "125-0004,7,order-1.csv\r\n".getBytes(StandardCharsets.UTF_8);
        deliver(folder, "order-1.csv", complete);

        assertThat(Files.exists(folder.resolve("order-1.csv.tmp-partial.new"))) // the retry never appends
                .as("the retry writes the complete file once").isFalse();
        assertThat(Files.readAllBytes(folder.resolve("order-1.csv"))).isEqualTo(complete);
        assertThat(names(folder)).as("the stale temp is never renamed to a final name")
                .containsExactly("order-1.csv", "order-1.csv.tmp-partial");
    }

    @Test
    void aMappedNameEscapingThroughDotDotIsRefusedAndNothingIsWritten() throws IOException {
        byte[] evil = "exfiltrated".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> deliver(folder, "../outside.csv", evil))
                .isInstanceOf(TargetFaultException.class)
                .extracting(f -> ((TargetFaultException) f).outcomeClass())
                .isEqualTo(OutcomeClass.PERMANENT);

        assertThat(Files.notExists(folder.getParent().resolve("outside.csv")))
                .as("@security: the escape target was never created").isTrue();
        assertThat(Files.notExists(folder.getParent().resolve("outside.csv.tmp-refused"))).isTrue();
        assertThat(names(folder)).as("@security: the folder holds nothing after the refusal").isEmpty();
    }

    @Test
    void aMissingTargetFolderIsRefusedAsPermanentAndCreatesNothing() throws IOException {
        Path absent = folder.resolve("absent");
        assertThatThrownBy(() -> deliver(absent, "row.csv", "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(TargetFaultException.class)
                .hasMessageContaining("is not available")
                .extracting(f -> ((TargetFaultException) f).outcomeClass())
                .isEqualTo(OutcomeClass.PERMANENT);

        assertThat(Files.notExists(absent)).as("nothing was created under the absent folder").isTrue();
        assertThat(names(folder)).isEmpty();
    }

    @Test
    void aFailedWriteNeverReplacesTheFinalNameWithPartialBytes() throws IOException {
        // a rename that cannot complete (the final name is an existing folder) leaves the final name untouched
        Files.createDirectory(folder.resolve("as-folder"));
        assertThatThrownBy(() -> deliver(folder, "as-folder", "complete".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(TargetFaultException.TransientFaultException.class);

        assertThat(Files.isDirectory(folder.resolve("as-folder")))
                .as("no partial bytes ever appeared under the final name").isTrue();
    }
}
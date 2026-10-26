package io.integrationplatform.routing.adapter.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * E16 T-150 hardening against a real temp folder: an existing final name is never overwritten (ADR-015 addendum),
 * a redelivery of the same bytes is an idempotent success, a name is a name (no folders, no absolute paths), a link
 * out of the folder is refused, and the save-side {@code rootPath} is the folder the adapter writes to.
 */
class FileTargetHardeningTest {

    @TempDir
    Path folder;

    private void deliver(Path directory, String fileName, byte[] body) {
        new FileTargetCall(
                send -> Optional.of(new FileMessage(new FileSettings(directory.toString()), fileName, body)),
                root -> Files.isDirectory(Path.of(root)) ? Optional.of(Path.of(root)) : Optional.empty(),
                CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(), ObservationRegistry.create())
                .call(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false));
    }

    private List<String> names(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @Tag("negative")
    void anExistingFinalFileWithOtherBytesIsNeverOverwritten() throws IOException {
        Files.write(folder.resolve("order-1.csv"), bytes("first"));

        assertThatThrownBy(() -> deliver(folder, "order-1.csv", bytes("second")))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));

        assertThat(Files.readAllBytes(folder.resolve("order-1.csv"))).isEqualTo(bytes("first"));
        assertThat(names(folder)).as("no temp file survives the refusal").containsExactly("order-1.csv");
    }

    @Test
    void aRedeliveryOfTheSameBytesIsAnIdempotentSuccess() throws IOException {
        deliver(folder, "order-2.csv", bytes("same"));
        deliver(folder, "order-2.csv", bytes("same")); // the ambiguous retry

        assertThat(Files.readAllBytes(folder.resolve("order-2.csv"))).isEqualTo(bytes("same"));
        assertThat(names(folder)).containsExactly("order-2.csv");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    void aNameIsAFileNameNotAPath() throws IOException {
        Files.createDirectory(folder.resolve("sub"));
        for (String name : List.of("sub/x.csv", "sub\\x.csv", folder.resolve("abs.csv").toString(), "..", ".")) {
            assertThatThrownBy(() -> deliver(folder, name, bytes("x")))
                    .as(name)
                    .isInstanceOfSatisfying(TargetFaultException.class,
                            fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        }
        assertThat(names(folder)).containsExactly("sub");
        assertThat(names(folder.resolve("sub"))).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    void aLinkOutOfTheFolderIsRefusedAndNothingLandsOutside() throws IOException {
        Path outside = Files.createTempDirectory("e16-outside");
        Path link = folder.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException noLinks) {
            Files.deleteIfExists(outside);
            assumeTrue(false, "this machine cannot create symbolic links: " + noLinks);
        }
        try {
            assertThatThrownBy(() -> deliver(folder, "escape/stolen.csv", bytes("x")))
                    .isInstanceOfSatisfying(TargetFaultException.class,
                            fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
            assertThat(names(outside)).isEmpty();
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void theSaveSidesRootPathIsTheFolderTheAdapterWritesTo() {
        assertThat(FileSettings.fromJson("{\"rootPath\":\"/data/out\"}").directory()).isEqualTo("/data/out");
        assertThat(FileSettings.fromJson("{\"directory\":\"/data/legacy\"}").directory()).isEqualTo("/data/legacy");
        assertThatThrownBy(() -> FileSettings.fromJson("{}")).isInstanceOf(IllegalStateException.class);
    }
}

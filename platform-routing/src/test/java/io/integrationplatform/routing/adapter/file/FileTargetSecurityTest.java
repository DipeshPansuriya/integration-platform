package io.integrationplatform.routing.adapter.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.FileRoots;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * E16 F4 (CWE-73/22) at the send: a File target's tenant-chosen root is confined to the operator's base folders (one
 * sub-folder per tenant), a file name cannot be a Windows device name or outgrow the file system, and the
 * existing-file comparison is bounded. Real temp folders, the real {@link FileRoots}, zero containers.
 */
class FileTargetSecurityTest {

    @TempDir
    Path workspace;

    private final UUID tenant = UUID.randomUUID();
    private Path base;
    private Path tenantRoot;
    private FileRoots roots;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createDirectories(workspace.resolve("exchange"));
        tenantRoot = Files.createDirectories(base.resolve(tenant.toString()));
        roots = FileRoots.of(List.of(base.toString()));
    }

    private void deliver(FileRoots with, String rootPath, String fileName, byte[] body) {
        new FileTargetCall(
                send -> Optional.of(new FileMessage(new FileSettings(rootPath), fileName, body)),
                root -> with.resolve(root, tenant),
                CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(), ObservationRegistry.create())
                .call(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false));
    }

    private void deliver(String rootPath, String fileName) {
        deliver(roots, rootPath, fileName, "x".getBytes(StandardCharsets.UTF_8));
    }

    private static void assertPermanent(Runnable send) {
        assertThatThrownBy(send::run).isInstanceOfSatisfying(TargetFaultException.class,
                fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
    }

    private List<String> names(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    @Tag("security")
    @DisplayName("A folder inside the tenant's own base sub-folder takes the file")
    void aFolderInsideTheTenantsRootIsWrittenTo() throws IOException {
        Path out = Files.createDirectories(tenantRoot.resolve("out"));

        deliver(out.toString(), "order-1.csv");

        assertThat(names(out)).containsExactly("order-1.csv");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("F4: a tenant-chosen root outside every base folder is refused PERMANENT and nothing is written there")
    void aRootOutsideTheBaseIsRefusedAndNothingIsWritten() throws IOException {
        Path startup = Files.createDirectories(workspace.resolve("startup"));

        assertPermanent(() -> deliver(startup.toString(), "evil.cmd"));

        assertThat(names(startup)).as("the application host folder stays untouched").isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("F4: another tenant's folder, the base itself and a .. escape are refused; nothing lands there")
    void otherTenantsAndTheBaseAreRefused() throws IOException {
        Path othersFolder = Files.createDirectories(base.resolve(UUID.randomUUID().toString()));

        for (String root : List.of(othersFolder.toString(), base.toString(),
                tenantRoot + "/../" + othersFolder.getFileName())) {
            assertPermanent(() -> deliver(root, "stolen.csv"));
        }

        assertThat(names(othersFolder)).isEmpty();
        assertThat(names(base)).as("only the two tenant folders").hasSize(2);
        assertThat(names(tenantRoot)).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("F4: with no base folder configured a File target refuses to send (fail closed)")
    void noBaseFolderConfiguredRefusesToSend() throws IOException {
        assertPermanent(() -> deliver(FileRoots.none(), tenantRoot.toString(), "a.csv", new byte[] {1}));

        assertThat(names(tenantRoot)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CON", "con", "NUL.txt", "prn.csv", "AUX", "COM1", "com9.log", "LPT1", "lpt9.csv",
            "CON .txt", "NUL.", "COM¹", "con.tar.gz", "COM0", "LPT0"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("A Windows reserved device name is not a file name, whatever the extension or case")
    void reservedDeviceNamesAreRefused(String name) throws IOException {
        assertPermanent(() -> deliver(tenantRoot.toString(), name));

        assertThat(names(tenantRoot)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"console.txt", "COM10", "LPT10", "nullable.csv", "aux-1.csv", "communications.csv", "CON1"})
    @DisplayName("Names that only resemble a device name are ordinary file names")
    void lookalikesAreAccepted(String name) throws IOException {
        deliver(tenantRoot.toString(), name);

        assertThat(names(tenantRoot)).containsExactly(name);
    }

    @Test
    @Tag("negative")
    @DisplayName("A file name whose temp name (name + .tmp-uuid) would pass 255 is refused PERMANENT; the longest allowed is written")
    void overlongNamesAreRefused() throws IOException {
        String longest = "a".repeat(214);

        assertPermanent(() -> deliver(tenantRoot.toString(), "a".repeat(215)));
        assertThat(names(tenantRoot)).isEmpty();

        deliver(tenantRoot.toString(), longest);
        assertThat(names(tenantRoot)).containsExactly(longest);
    }

    @Test
    @Tag("negative")
    @DisplayName("A name that is short in characters but long in UTF-8 bytes is refused as well")
    void multiByteNamesAreMeasuredInBytes() throws IOException {
        assertPermanent(() -> deliver(tenantRoot.toString(), "é".repeat(120)));

        assertThat(names(tenantRoot)).isEmpty();
    }

    @Test
    @Tag("security")
    @DisplayName("The existing-file comparison is bounded: other size is a name clash with no read, over the limit is PERMANENT")
    void existingFileComparisonIsBounded() throws IOException {
        Path target = tenantRoot.resolve("big.csv");
        Files.write(target, new byte[100]);

        assertThatThrownBy(() -> FileWrite.deliver(target, new byte[99], 1000))
                .isInstanceOf(FileWrite.FileNameTakenException.class);
        assertThatThrownBy(() -> FileWrite.deliver(target, new byte[100], 50))
                .isInstanceOf(FileWrite.FileTooLargeToVerifyException.class);
        FileWrite.deliver(target, new byte[100], 1000); // the same bytes within the limit: idempotent success

        assertThat(names(tenantRoot)).as("no temp file survives any of the three").containsExactly("big.csv");
        assertThat(FileWrite.COMPARE_LIMIT).isEqualTo(16 * 1024 * 1024);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("R5 (CWE-73/918): a name with a separator or .. is refused as a name BEFORE the folder is probed on disk")
    void anImpureFileNameIsRefusedAsANameBeforeTheFolderIsProbedOnDisk() throws IOException {
        for (String name : List.of("../escape.csv", "..\\escape.csv", "sub/escape.csv", "sub\\escape.csv")) {
            try {
                deliver(tenantRoot.toString(), name);
                throw new AssertionError("the mapped name " + name + " should have been refused");
            } catch (TargetFaultException fault) {
                assertThat(fault.getMessage()).as(name).contains("is not a plain file name")
                        .doesNotContain("outside the target's folder");
            }
        }

        assertThat(names(tenantRoot)).as("nothing is written anywhere").isEmpty();
    }

    @Test
    @Tag("security")
    @DisplayName("TOCTOU: the write uses the path the guard resolved, not the settings text, so it lands where the guard looked")
    void theWriteUsesTheResolvedPath() throws IOException {
        Path out = Files.createDirectories(tenantRoot.resolve("out"));
        Path real = out.toRealPath();
        FolderResolver resolver = root -> Optional.of(real); // the guard answers a real path whatever the text says

        new FileTargetCall(send -> Optional.of(new FileMessage(new FileSettings("any-text"), "r.csv", new byte[] {7})),
                resolver, CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(), ObservationRegistry.create())
                .call(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false));

        assertThat(Map.of("names", names(out))).containsEntry("names", List.of("r.csv"));
    }
}

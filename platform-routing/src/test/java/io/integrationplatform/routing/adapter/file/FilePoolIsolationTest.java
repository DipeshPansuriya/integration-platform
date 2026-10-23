package io.integrationplatform.routing.adapter.file;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.routing.poolisolation.CountingTransactionManager;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T175 (T71 for FILE): the settings and content reads are one short transaction; the file write holds none. */
class FilePoolIsolationTest {

    @TempDir
    Path folder;

    @Test
    void noTransactionIsOpenWhileTheFileIsWritten() throws Exception {
        CountingTransactionManager pool = new CountingTransactionManager();
        AtomicInteger openDuringReads = new AtomicInteger(-1);
        AtomicInteger openAtFolderResolve = new AtomicInteger(-1);
        FileOutbound reads = send -> {
            openDuringReads.set(pool.open());
            return Optional.of(new FileMessage(new FileSettings(folder.toString()), "a.csv",
                    "x".getBytes(StandardCharsets.UTF_8)));
        };
        FileTargetCall call = FileAdapterConfiguration.compose(reads, root -> {
            openAtFolderResolve.set(pool.open());
            return Optional.of(Path.of(root));
        }, pool, CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(), ObservationRegistry.NOOP);

        call.call(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false));

        assertThat(openDuringReads.get()).as("the reads ran inside a short transaction").isEqualTo(1);
        assertThat(openAtFolderResolve.get()).as("pooled connections held once the reads are over").isZero();
        assertThat(pool.begun()).isEqualTo(1);
        assertThat(pool.open()).isZero();
        assertThat(Files.readString(folder.resolve("a.csv"))).isEqualTo("x");
    }

    private static FileTargetCall composed(org.springframework.transaction.PlatformTransactionManager pool,
            FileOutbound reads) {
        return FileAdapterConfiguration.compose(reads, root -> Optional.of(Path.of(root)), pool,
                CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    private static final Stage2Send SEND = new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
            false);

    @Test
    void aPoolTimeoutWhileBeginningTheResolveTransactionIsTransientNotAnInexpressibleFile() {
        // T190 item 4
        org.springframework.transaction.PlatformTransactionManager broken =
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(broken.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.transaction.CannotCreateTransactionException("pool timeout"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> composed(broken, s -> Optional.empty()).call(SEND))
                .isInstanceOf(io.integrationplatform.routing.stage2.TargetFaultException.class)
                .extracting(f -> ((io.integrationplatform.routing.stage2.TargetFaultException) f).outcomeClass())
                .isEqualTo(io.integrationplatform.domain.delivery.OutcomeClass.TRANSIENT);
    }

    @Test
    void aTransientDataAccessErrorInResolveIsTransientButAFormatErrorStaysPermanent() {
        CountingTransactionManager pool = new CountingTransactionManager();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> composed(pool, s -> {
                    throw new org.springframework.dao.QueryTimeoutException("statement timeout");
                }).call(SEND))
                .extracting(f -> ((io.integrationplatform.routing.stage2.TargetFaultException) f).outcomeClass())
                .isEqualTo(io.integrationplatform.domain.delivery.OutcomeClass.TRANSIENT);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> composed(pool, s -> {
                    throw new IllegalArgumentException("bad name");
                }).call(SEND))
                .extracting(f -> ((io.integrationplatform.routing.stage2.TargetFaultException) f).outcomeClass())
                .isEqualTo(io.integrationplatform.domain.delivery.OutcomeClass.PERMANENT);
    }
}

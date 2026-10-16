package io.integrationplatform.ingest.internal.intake;

import io.integrationplatform.ingest.port.AcceptedMessage;
import io.integrationplatform.ingest.port.IntakeRefusedException;
import io.integrationplatform.ingest.port.IntakeTransaction;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.ingest.port.StageTimings;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The intake transaction implementation (E11 A1 narrow, T-048; ADR-011 Decision 4, steps in order):
 * resolve published flow -> pin check -> business key -> the acceptance write (message + payload, one statement)
 * -> the outbox append (reuse of the frozen {@code OutboxWriter}, ADR-011 Decision 5) -> the INTAKE stage timing
 * row. Every step is tenant-scoped (R11), so all of them need a transaction: the {@code @Transactional} on
 * {@link #accept} is that commit boundary (REQUIRED - it joins an active caller transaction, as the container
 * proof does, and starts one for the HTTP path). A refusal throws before the write, a failure anywhere rolls
 * the whole commit back ("no tracking id without the commit", E11-F1-S1).
 */
public class IntakeService implements IntakeTransaction {

    private final IntakeStore store;
    private final OutboxWriter outbox;
    private final StageTimings timings;

    public IntakeService(JdbcClient jdbcClient, TenantContext tenantContext, OutboxWriter outbox,
            StageTimings timings) {
        this.store = new IntakeStore(jdbcClient, tenantContext);
        this.outbox = outbox;
        this.timings = timings;
    }

    @Override
    @Transactional
    public AcceptedMessage accept(IntakeRequest request) {
        IntakeStore.PublishedInterface published = store.resolvePublishedInterface(request.interfaceCode())
                .orElseThrow(() -> new IntakeRefusedException(
                        IntakeRefusedException.Reason.INTERFACE_NOT_FOUND_OR_NOT_PUBLISHED,
                        "no published interface '" + request.interfaceCode() + "' on this tenant"));
        IntakeStore.PublishedVersion version = store.resolvePublishedVersion(published)
                .orElseThrow(() -> new IntakeRefusedException(
                        IntakeRefusedException.Reason.INTERFACE_NOT_FOUND_OR_NOT_PUBLISHED,
                        "interface '" + request.interfaceCode() + "' has no published version"));
        String pinnedLabel = store.pinnedLabel(version.sourceContractVersionId()).orElseThrow(
                () -> new IntakeRefusedException(IntakeRefusedException.Reason.CONTRACT_PIN_MISMATCH,
                        "the interface's pinned source contract version is not approved"));
        if (!pinnedLabel.equals(request.sourceContractVersion())) {
            throw new IntakeRefusedException(IntakeRefusedException.Reason.CONTRACT_PIN_MISMATCH,
                    "the interface pins source contract " + pinnedLabel);
        }
        String businessKey = BusinessKeyRule.resolve(version.businessKeyRule(), request.payload());
        UUID messageId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        store.accept(messageId, published.interfaceId(), version.interfaceVersionId(),
                version.sourceContractVersionId(), businessKey, request.payload().toString());
        // ADR-011 Decision 5: the record that sets stage 1 in motion is part of the same commit.
        long outboxId = outbox.append(new OutboxEntry(OutboxTopic.MESSAGE_ACCEPTED, messageId, businessKey,
                null, null, version.sourceContractVersionId()));
        timings.record(messageId, StageTimings.Stage.INTAKE, startedAt, Instant.now());
        return new AcceptedMessage(messageId, outboxId);
    }
}
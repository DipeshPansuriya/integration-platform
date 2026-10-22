package io.integrationplatform.ingest.internal.intake;

import io.integrationplatform.config.flow.api.MaskedFields;
import io.integrationplatform.ingest.port.AcceptedMessage;
import io.integrationplatform.ingest.port.CorrelationContext;
import io.integrationplatform.ingest.port.IntakeRefusedException;
import io.integrationplatform.ingest.port.IntakeTransaction;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.ingest.port.StageTimings;
import io.integrationplatform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The intake transaction implementation (E11 A1 narrow, T-048; ADR-011 Decision 4, steps in order):
 * Idempotency-Key replay (T-187) -> resolve published flow -> pin check -> business keys -> per-key ordering
 * sequence (T-196, skipped for NONE) -> per valid record the acceptance write, the outbox append and the INTAKE
 * timing -> the replay row. Every step is tenant-scoped (R11), so all of them need a transaction: the
 * {@code @Transactional} methods are that commit boundary. A single call is a bulk of one whose rejection throws.
 */
public class IntakeService implements IntakeTransaction {

    /**
     * BS-06.4 (row 375, base suite run 8): the record's business key rides the message_store's btree indexes
     * (message_ordering_idx, the ordering_key unique), where an oversized key blew the transaction into the
     * 503 INTAKE_UNAVAILABLE catch-all (PSQLException "index row requires N bytes, maximum size is 8191" on the
     * ordering_key upsert). Intake refuses such a record deliberately 422 BEFORE the ordering plan; the value
     * (~2 KB, well under the ~8 KB store bound every probe showed) is the operator's to raise with evidence.
     */
    static final int MAX_BUSINESS_KEY_BYTES = 2048;

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<RecordResult>> RESULTS = new TypeReference<>() { };

    private final TenantContext tenantContext;
    private final IntakeStore store;
    private final OutboxWriter outbox;
    private final StageTimings timings;

    public IntakeService(JdbcClient jdbcClient, TenantContext tenantContext, OutboxWriter outbox,
            StageTimings timings) {
        this.tenantContext = tenantContext;
        this.store = new IntakeStore(jdbcClient, tenantContext);
        this.outbox = outbox;
        this.timings = timings;
    }

    @Override
    @Transactional
    public AcceptedMessage accept(IntakeRequest request) {
        Run run = run(new BulkRequest(request.interfaceCode(), request.sourceContractVersion(),
                List.of(request.payload()), request.idempotencyKey(), request.credentialRef()), true);
        RecordResult only = run.results().get(0);
        if (!RecordResult.ACCEPTED.equals(only.status())) {
            throw new IntakeRefusedException(IntakeRefusedException.Reason.valueOf(only.code()), only.message());
        }
        return new AcceptedMessage(only.trackingId(), only.outboxId(), run.sync());
    }

    @Override
    @Transactional
    public List<RecordResult> acceptBulk(BulkRequest request) {
        return run(request, false).results();
    }

    /** {@code sync}: the caller may wait for the inline answer (a SYNC interface, single record, not a replay). */
    private record Run(List<RecordResult> results, boolean sync) {
    }

    private Run run(BulkRequest request, boolean inline) {
        String key = request.idempotencyKey();
        boolean keyed = key != null && !key.isBlank();
        String credential = request.credentialRef() == null ? "" : request.credentialRef();
        String hash = null;
        if (keyed) {
            hash = IntakeKeys.requestHash(request.interfaceCode(), request.sourceContractVersion(),
                    request.records().stream().map(JsonNode::toString).toList());
            store.purgeExpired();
            Optional<IntakeStore.StoredResults> prior = store.findIdempotency(credential, key);
            if (prior.isPresent()) {
                if (!prior.get().requestHash().equals(hash)) {
                    throw new IntakeRefusedException(IntakeRefusedException.Reason.IDEMPOTENCY_KEY_REUSED,
                            "the Idempotency-Key was already used with another request body");
                }
                return new Run(JSON.readValue(prior.get().results(), RESULTS), false);
            }
        }
        IntakeStore.PublishedInterface published = store.resolvePublishedInterface(request.interfaceCode())
                .orElseThrow(() -> new IntakeRefusedException(
                        IntakeRefusedException.Reason.INTERFACE_NOT_FOUND_OR_NOT_PUBLISHED,
                        "no published interface '" + request.interfaceCode() + "' on this tenant"));
        IntakeStore.PublishedVersion version = store.resolvePublishedVersion(published)
                .orElseThrow(() -> new IntakeRefusedException(
                        IntakeRefusedException.Reason.INTERFACE_NOT_FOUND_OR_NOT_PUBLISHED,
                        "interface '" + request.interfaceCode() + "' has no published version"));
        List<JsonNode> records = request.records();
        // T-066 (T108): each payload's masked values are scrubbed from every later log line, whoever writes it.
        records.forEach(record -> MaskedFields.register(MaskedFields.valuesOf(record, version.maskedFields())));
        String pinnedLabel = store.pinnedLabel(version.sourceContractVersionId()).orElseThrow(
                () -> new IntakeRefusedException(IntakeRefusedException.Reason.CONTRACT_PIN_MISMATCH,
                        "the interface's pinned source contract version is not approved"));
        if (!pinnedLabel.equals(request.sourceContractVersion())) {
            throw new IntakeRefusedException(IntakeRefusedException.Reason.CONTRACT_PIN_MISMATCH,
                    "the interface pins source contract " + pinnedLabel);
        }
        int n = records.size();
        RecordResult[] results = new RecordResult[n];
        String[] keys = new String[n];
        for (int i = 0; i < n; i++) {
            try {
                keys[i] = BusinessKeyRule.resolve(version.businessKeyRule(), records.get(i));
            } catch (IntakeRefusedException unresolved) {
                results[i] = RecordResult.rejected(i, unresolved.reason().name(), unresolved.detail());
                continue;
            }
            // BS-06.4: before the ordering plan - an over-bound key would enter ordering_key's btree and blow the
            // transaction into the 503 catch-all; it is refused here instead, nothing persisted.
            int keyBytes = keys[i].getBytes(StandardCharsets.UTF_8).length;
            if (keyBytes > MAX_BUSINESS_KEY_BYTES) {
                results[i] = RecordResult.rejected(i, IntakeRefusedException.Reason.BUSINESS_KEY_TOO_LONG.name(),
                        "the business key is " + keyBytes + " bytes over the " + MAX_BUSINESS_KEY_BYTES
                                + " byte intake key cap");
                keys[i] = null;
            }
        }
        List<String> validKeys = Arrays.stream(keys).filter(Objects::nonNull).toList();
        Map<String, Long> nextSeq = new HashMap<>();
        if (IntakeKeys.ordered(published.ordering()) && !validKeys.isEmpty()) {
            // T-196: a busy key fails inside the 2 s budget (503 + Retry-After) instead of queueing the caller.
            store.lockTimeout();
            IntakeKeys.orderingPlan(validKeys).forEach((businessKey, count) -> nextSeq.put(businessKey,
                    store.nextSeq(published.interfaceId(), businessKey, count) - count + 1));
        }
        CorrelationContext.tag(CorrelationContext.TENANT_ID, tenantContext.requireTenantId());
        for (int i = 0; i < n; i++) {
            if (keys[i] == null) {
                continue;
            }
            Long keySeq = nextSeq.get(keys[i]);
            if (keySeq != null) {
                nextSeq.put(keys[i], keySeq + 1);
            }
            UUID messageId = UUID.randomUUID();
            CorrelationContext.tag(CorrelationContext.MESSAGE_ID, messageId);
            Instant startedAt = Instant.now();
            Instant deferredUntil = IntakeKeys.deferredUntil(published.mode(), inline, startedAt);
            store.accept(messageId, published.interfaceId(), version.interfaceVersionId(),
                    version.sourceContractVersionId(), keys[i], records.get(i).toString(), keySeq);
            // ADR-011 Decision 5: the record that sets stage 1 in motion is part of the same commit; it carries
            // the request's journey (T107) - the relay restores the traceparent as the record's Kafka header.
            long outboxId = outbox.append(new OutboxEntry(OutboxTopic.MESSAGE_ACCEPTED, messageId, keys[i],
                    CorrelationContext.currentTraceparent(), deferredUntil, version.sourceContractVersionId()));
            timings.record(messageId, StageTimings.Stage.INTAKE, startedAt, Instant.now());
            log.info("intake accepted message {}", messageId); // T107: the journey line per message (id + correlation MDC); terse
            results[i] = RecordResult.accepted(i, messageId, outboxId);
        }
        List<RecordResult> answer = List.of(results);
        if (keyed && !validKeys.isEmpty()) {
            store.saveIdempotency(credential, key, hash, JSON.writeValueAsString(answer));
        }
        log.info("intake: {} of {} records accepted on interface {}", validKeys.size(), n, request.interfaceCode());
        return new Run(answer, inline && "SYNC".equals(published.mode()));
    }
}

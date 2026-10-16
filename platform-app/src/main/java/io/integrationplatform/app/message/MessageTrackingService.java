package io.integrationplatform.app.message;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * E13 N1 (T110 RED skeleton): the message timeline and the failures list - not built yet.
 */
public class MessageTrackingService {

    /** One recorded stage of a message: its timestamps, its own duration and the gap since the previous stage. */
    public record StageTime(String stage, String startedAt, String finishedAt, Long durationMs, Long sincePreviousMs) {
    }

    /** One message's journey: every recorded stage in pipeline order, and the total from first start to last end. */
    public record Timeline(String trackingId, String flowId, String state, String acceptedAt, List<StageTime> stages,
            Long totalMs) {
    }

    /** One failed or dead-lettered message: where it failed, the error class and the last error text. */
    public record Failure(String trackingId, String deliveryId, String flowId, String targetId, String failingStage,
            String errorClass, String lastError, String origin, String failedAt) {
    }

    private final JdbcMessageTrackingStore store;

    public MessageTrackingService(JdbcMessageTrackingStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<Timeline> timeline(UUID trackingId) {
        throw new UnsupportedOperationException("T110 RED: the timeline read is not built yet");
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Failure> failures(UUID flowId, LocalDate day) {
        throw new UnsupportedOperationException("T110 RED: the failures read is not built yet");
    }
}

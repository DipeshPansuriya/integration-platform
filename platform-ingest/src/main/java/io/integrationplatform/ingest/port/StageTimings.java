package io.integrationplatform.ingest.port;

import java.time.Instant;
import java.util.UUID;

/**
 * The append-only stage timing log (E11 A1 narrow, ADR-014's message-store family): one row per stage per message
 * in {@code public.message_stage_timing} (T-048), read later by the message timeline (E13), the reconciler (E12)
 * and the delivery screens (F10). Writers other than intake (ROUTE, TRANSFORM, DELIVER) call {@link #record}
 * inside their own transaction; intake writes its INTAKE row in the acceptance transaction itself. A delivery's
 * final move writes DELIVER through {@link #recordDeliverFinished} (T105), because that stage runs per delivery.
 *
 * <p>Append-only on purpose: the table grants {@code SELECT, INSERT} to the application role and refuses
 * {@code UPDATE}/{@code DELETE}/{@code TRUNCATE} - a corrected timing would not be a timing, it would be a lie
 * after the fact. The stage enum is the closed set the table's CHECK carries.
 */
public interface StageTimings {

    /**
     * Records one stage's timing for a message.
     *
     * @param messageId the message the stage belongs to (its tracking id)
     * @param stage which stage of the pipeline ran
     * @param startedAt when the stage started
     * @param finishedAt when the stage finished, or {@code null} while it is still running
     */
    void record(UUID messageId, Stage stage, Instant startedAt, Instant finishedAt);

    /**
     * Records the DELIVER stage of the message a delivery belongs to (T105), in the transaction of that delivery's
     * final move: {@code DELIVERED}, or {@code DEAD_LETTERED} - a failure records on DELIVER, finished at the
     * failure. The table keeps one row per message and stage while a message fans out to many deliveries, each
     * retried and replayable, so the row is written by the final move that leaves none of the message's deliveries
     * open. It starts at the message's first delivery attempt (at {@code finishedAt} when no call was made) and
     * never after {@code finishedAt}. An open sibling, a delivery without a stage-1 message, a row already written
     * (a replayed dead letter) or a delivery the bound tenant cannot see records nothing and never raises: the
     * delivery's own move must not fail on its timing. Every final move holds the message row lock first (the one
     * lock order, T-165), so two siblings finishing together can neither both miss the row nor both write it.
     *
     * @param deliveryId the delivery whose final move this transaction has just made
     * @param finishedAt the instant of that final move
     */
    void recordDeliverFinished(UUID deliveryId, Instant finishedAt);

    /** The pipeline's stages - the table's CHECK constraint is this enum's names. */
    enum Stage {
        /** The common intake API accepting the message (written by the intake transaction). */
        INTAKE,
        /** Stage 1: the route was chosen (written by the route stage, another lane). */
        ROUTE,
        /** The mapping/transform ran (written by the transform lane, another lane). */
        TRANSFORM,
        /** The target adapter attempted delivery (written by the delivery stage, another lane). */
        DELIVER
    }
}
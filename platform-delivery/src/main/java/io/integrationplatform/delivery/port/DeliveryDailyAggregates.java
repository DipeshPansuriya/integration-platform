package io.integrationplatform.delivery.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * T-138 (E13-F1-S2): the daily delivery pre-aggregate of the bound tenant - per interface / target / UTC day. Every
 * method needs a bound tenant and an active transaction.
 */
public interface DeliveryDailyAggregates {

    /** Rebuilds the bound tenant's rows of {@code day} from delivery + delivery_attempt; the rows written. */
    int refresh(LocalDate day);

    /** The stored roll-up of {@code day}. */
    List<DailyCounts> aggregated(LocalDate day);

    /** The same counts taken directly from delivery + delivery_attempt now - the reconciliation check. */
    List<DailyCounts> direct(LocalDate day);

    /** One interface / target of one day; {@code retriesMade} counts the attempts after the first. */
    record DailyCounts(UUID interfaceId, UUID targetId, long successes, long failuresTransient, long failuresAmbiguous,
            long failuresPermanent, long retriesMade, long retriedThenSucceeded, long stillStuck) {
    }
}

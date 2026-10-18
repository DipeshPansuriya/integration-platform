package io.integrationplatform.app.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.domain.delivery.DeliveryState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T112 round 2 (row 328 M1 + row 329 M3), without Docker - the store is a mock, so only the service's rules are
 * under test; the tenant-scoped reads themselves are proved by {@code MessageTrackingContainerTest}.
 * <ul>
 *   <li>M1: the timeline names its failing stage from SERVER-side state, never from a client hint -
 *       {@code ROUTE_FAILED} is ROUTE, a message with a DEAD_LETTERED delivery is DELIVER, anything else (in flight,
 *       delivered) is null;</li>
 *   <li>M3: one entry per delivery with the target's response time - the LAST attempt's
 *       {@code finished - started} and its outcome class - and a null duration when the delivery has no attempt.</li>
 * </ul>
 * Not tagged container (R03).
 */
class MessageTrackingServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");
    private static final String TARGET_1 = "00000000-0000-4000-8000-0000000000a1";
    private static final String TARGET_2 = "00000000-0000-4000-8000-0000000000b2";

    private final JdbcMessageTrackingStore store = mock(JdbcMessageTrackingStore.class);
    private final MessageTrackingService service = new MessageTrackingService(store);

    private UUID message(String state, JdbcMessageTrackingStore.DeliveryAttemptRow... deliveries) {
        UUID id = UUID.randomUUID();
        when(store.message(id)).thenReturn(Optional.of(
                new JdbcMessageTrackingStore.MessageRow(id.toString(), UUID.randomUUID().toString(), state, T0)));
        when(store.stages(id)).thenReturn(List.of(
                new JdbcMessageTrackingStore.StageRow("INTAKE", T0, T0.plusMillis(40))));
        when(store.deliveries(id)).thenReturn(List.of(deliveries));
        return id;
    }

    /** One delivery row joined to one of its attempts (attempt fields null: the delivery has no attempt). */
    private static JdbcMessageTrackingStore.DeliveryAttemptRow row(String deliveryId, String targetId, String state,
            Long attemptId, Instant startedAt, Instant finishedAt, String outcome) {
        return new JdbcMessageTrackingStore.DeliveryAttemptRow(deliveryId, targetId, state, attemptId, startedAt,
                finishedAt, outcome);
    }

    private MessageTrackingService.Timeline read(UUID id) {
        return service.timeline(id).orElseThrow();
    }

    // ------------------------------------------------------------------ M1: failingStage

    @Test
    void aRoutedMessageWithADeadLetteredDeliveryFailedAtDeliver() {
        UUID id = message("ROUTED",
                row("d-1", TARGET_1, "DEAD_LETTERED", 7L, T0.plusSeconds(1), T0.plusSeconds(2), "PERMANENT"));

        assertThat(read(id).failingStage()).isEqualTo("DELIVER");
    }

    @Test
    void aRouteFailedMessageFailedAtRoute() {
        UUID id = message("ROUTE_FAILED");

        assertThat(read(id).failingStage()).isEqualTo("ROUTE");
    }

    @Test
    void anInFlightRoutedMessageHasNoFailingStage() {
        UUID id = message("ROUTED",
                row("d-1", TARGET_1, "RETRY_WAIT", 7L, T0.plusSeconds(1), T0.plusSeconds(31), "TRANSIENT"));

        assertThat(read(id).failingStage()).isNull();
    }

    @Test
    void aDeliveredMessageAndAnAcceptedMessageHaveNoFailingStage() {
        UUID delivered = message("ROUTED",
                row("d-1", TARGET_1, "DELIVERED", 7L, T0.plusSeconds(1), T0.plusMillis(1412), "SUCCESS"));
        UUID accepted = message("ACCEPTED");

        assertThat(read(delivered).failingStage()).isNull();
        assertThat(read(accepted).failingStage()).isNull();
    }

    @Test
    void oneDeadLetteredDeliveryAmongSeveralStillFailsTheMessageAtDeliver() {
        UUID id = message("ROUTED",
                row("d-1", TARGET_1, "DELIVERED", 1L, T0, T0.plusMillis(100), "SUCCESS"),
                row("d-2", TARGET_2, "DEAD_LETTERED", 2L, T0, T0.plusMillis(200), "PERMANENT"));

        assertThat(read(id).failingStage()).isEqualTo("DELIVER");
    }

    // ------------------------------------------------------------------ M3: deliveries (target response)

    @Test
    void theTargetResponseIsTheLastAttemptsSentToAnsweredTimeAndOutcome() {
        // two attempts, the LATER one listed first: the pick is by finish time, never by row order
        UUID id = message("ROUTED",
                row("d-1", TARGET_1, "DELIVERED", 9L, T0.plusSeconds(60), T0.plusSeconds(60).plusMillis(412), "SUCCESS"),
                row("d-1", TARGET_1, "DELIVERED", 8L, T0, T0.plusSeconds(30), "TRANSIENT"));

        assertThat(read(id).deliveries()).containsExactly(
                new MessageTrackingService.DeliveryTime("d-1", TARGET_1, 412L, "SUCCESS", DeliveryState.DELIVERED));
    }

    @Test
    void twoAttemptsFinishingTogetherPickTheLaterAttemptId() {
        UUID id = message("ROUTED",
                row("d-1", TARGET_1, "DEAD_LETTERED", 5L, T0, T0.plusMillis(300), "TRANSIENT"),
                row("d-1", TARGET_1, "DEAD_LETTERED", 6L, T0.plusMillis(250), T0.plusMillis(300), "PERMANENT"));

        assertThat(read(id).deliveries()).extracting(MessageTrackingService.DeliveryTime::outcome)
                .containsExactly("PERMANENT");
    }

    @Test
    void aDeliveryWithNoAttemptHasANullDurationAndOutcome() {
        UUID id = message("ROUTED", row("d-1", TARGET_1, "PENDING", null, null, null, null));

        assertThat(read(id).deliveries()).containsExactly(
                new MessageTrackingService.DeliveryTime("d-1", TARGET_1, null, null, DeliveryState.PENDING));
    }

    @Test
    void everyDeliveryOfAFanOutGetsItsOwnEntryOrderedByTarget() {
        UUID id = message("ROUTED",
                row("d-2", TARGET_2, "DELIVERED", 2L, T0, T0.plusMillis(80), "SUCCESS"),
                row("d-1", TARGET_1, "PENDING", null, null, null, null));

        assertThat(read(id).deliveries()).extracting(MessageTrackingService.DeliveryTime::targetId)
                .containsExactly(TARGET_1, TARGET_2);
    }

    @Test
    void aMessageWithNoDeliveryHasNoEntries() {
        assertThat(read(message("ROUTE_FAILED")).deliveries()).isEmpty();
    }

    @Test
    void aMessageTheTenantCannotSeeAsksNoFurtherRead() {
        UUID foreign = UUID.randomUUID();
        when(store.message(foreign)).thenReturn(Optional.empty());

        assertThat(service.timeline(foreign)).isEmpty();
        verify(store, never()).stages(any());
        verify(store, never()).deliveries(any());
    }

    // ------------------------------------------------------------------ T165 M1: each delivery carries its own state

    @Test
    void everyDeliveryCarriesItsOwnStateEvenWithoutAnAttempt() {
        UUID id = message("ROUTED",
                row("d-1", TARGET_1, "RETRY_WAIT", 1L, T0, T0.plusMillis(10), "TRANSIENT"),
                row("d-2", TARGET_2, "DEAD_LETTERED", 2L, T0, T0.plusMillis(20), "TRANSIENT"),
                row("d-3", "00000000-0000-4000-8000-0000000000c3", "DELIVERED", 3L, T0, T0.plusMillis(30), "SUCCESS"),
                row("d-4", "00000000-0000-4000-8000-0000000000d4", "PENDING", null, null, null, null));

        assertThat(read(id).deliveries())
                .extracting(MessageTrackingService.DeliveryTime::deliveryId, MessageTrackingService.DeliveryTime::state)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("d-1", DeliveryState.RETRY_WAIT),
                        org.assertj.core.groups.Tuple.tuple("d-2", DeliveryState.DEAD_LETTERED),
                        org.assertj.core.groups.Tuple.tuple("d-3", DeliveryState.DELIVERED),
                        org.assertj.core.groups.Tuple.tuple("d-4", DeliveryState.PENDING));
    }

    @Test
    void theStateIsARequiredFieldOfTheTrackingApi() throws Exception {
        io.swagger.v3.oas.annotations.media.Schema schema = MessageTrackingService.DeliveryTime.class
                .getDeclaredField("state").getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
        assertThat(schema).as("@Schema on DeliveryTime.state").isNotNull();
        assertThat(schema.requiredMode()).isEqualTo(io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED);
    }
}

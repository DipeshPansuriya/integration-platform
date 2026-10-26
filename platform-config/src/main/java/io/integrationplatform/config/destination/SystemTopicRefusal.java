package io.integrationplatform.config.destination;

import java.util.List;
import java.util.Optional;

/**
 * Karan SF-2 (T-184; ADR-011 Decision 7's closed register): the platform's own record topics -
 * {@code platform.message.accepted}, {@code platform.delivery.due}, {@code platform.poison} - carry no tenant
 * data by design, and the partition rules (ADR-014's day partitions, the poison register) do not purge a
 * tenant's business data. A KAFKA system tuned onto one of them would pour tenant payloads into platform
 * infrastructure, so its SAVE is refused with the reason - at the save path, like every other configuration
 * fault this wave refuses (ADR-010 section 5's "refused at save" register).
 *
 * <p>This is the rule, kept out of the kafkatopic adapter (not this lane's file): its save path calls
 * {@link #topicProblem} with the requested topic text. A topic is refused too when it is blank or carries
 * anything but a Kafka topic name (letters, digits, `.`, `_`, `-` - the broker's own validity rule).
 */
public final class SystemTopicRefusal {

    /** The platform's record topics: the closed register of ADR-005 (the value the relay publishes onto). */
    public static final List<String> PLATFORM_TOPICS = List.of("platform.message.accepted", "platform.delivery.due",
            "platform.poison");

    private SystemTopicRefusal() {
    }

    /** The refusal reason for {@code topic}, or none. */
    public static Optional<String> topicProblem(String topic) {
        if (topic == null || topic.isBlank()) {
            return Optional.of("a topic is required");
        }
        String name = topic.strip();
        String shown = clip(name);
        if (PLATFORM_TOPICS.stream().anyMatch(t -> t.equalsIgnoreCase(name))) {
            return Optional.of(shown + " is a platform topic - the platform's own register is never a tenant "
                    + "destination (T-184 SF-2)");
        }
        if (name.toLowerCase().startsWith("platform.") || name.toLowerCase().startsWith("platform_")) {
            return Optional.of(shown + " rides the platform's record register - not a tenant topic (T-184 SF-2)");
        }
        if (!name.matches("[A-Za-z0-9._\\-]+")) {
            return Optional.of(shown + " is not a Kafka topic name (letters, digits, dot, underscore, hyphen)");
        }
        return Optional.empty();
    }

    /** The echoed topic name only: control characters flattened, at most 64 characters plus an ellipsis. */
    private static String clip(String text) {
        String flat = text.replaceAll("\\p{Cntrl}", "_");
        return flat.length() > 64 ? flat.substring(0, 64) + "..." : flat;
    }
}

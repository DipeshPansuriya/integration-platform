package io.integrationplatform.ingest.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Seam lock (E2, arjun-fullstack): the topic register of the seam port and the {@code CHECK} of the seam migration
 * {@code V2_0_1__outbox.sql} are the same closed set, so a lane cannot drift one without failing the other (R10:
 * text + CHECK, never ENUM). Lanes: do not edit this test - a needed topic addition goes through arjun-fullstack.
 */
class OutboxTopicTest {

    private static final Pattern CHECK_IN_LIST =
            Pattern.compile("topic\\s+text\\s+NOT NULL CHECK \\(topic IN \\(([^)]*)\\)\\)");

    @Test
    void namesAreUniqueAndTopicNameIsNeverBlank() {
        Set<String> names = new LinkedHashSet<>();
        for (OutboxTopic topic : OutboxTopic.values()) {
            assertThat(topic.topicName()).as("topic name of %s", topic).isNotBlank();
            assertThat(names).as("no duplicate topic name for %s", topic).doesNotContain(topic.topicName());
            names.add(topic.topicName());
        }
    }

    @Test
    void enumRegisterMatchesTheOutboxMigrationCheckList() throws IOException {
        // The classpath location is exactly what Flyway applies - so this also proves V2_0_1 travels inside
        // platform-ingest and will be found by the application's migration scan.
        String sql;
        try (InputStream in = OutboxTopicTest.class.getResourceAsStream("/db/migration/V2_0_1__outbox.sql")) {
            assertThat(in).as("V2_0_1__outbox.sql on the classpath").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Matcher check = CHECK_IN_LIST.matcher(sql);
        assertThat(check.find()).as("the outbox topic CHECK IN (...) list in V2_0_1").isTrue();
        Set<String> allowed = new LinkedHashSet<>();
        for (String quoted : check.group(1).split(",")) {
            allowed.add(quoted.trim().replace("'", ""));
        }
        Set<String> register = new LinkedHashSet<>();
        for (OutboxTopic topic : OutboxTopic.values()) {
            register.add(topic.topicName());
        }
        assertThat(register).containsExactlyInAnyOrderElementsOf(allowed);
    }
}

package io.integrationplatform.e2e.release1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * T174c: the read-only SQL oracle the scenarios use where the API does not expose a fact (append-only attempt rows,
 * the contract version a delivery is pinned to, its event trail). It runs {@code psql} inside the runner's Postgres
 * container ({@code -De2e.pgContainer}); the login is the container's own owner role, so no password travels on a
 * command line and nothing here writes. The statements are SELECTs built from UUIDs the scenarios just read.
 */
final class Release1Sql {

    private static final java.util.regex.Pattern UUID_TEXT =
            java.util.regex.Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private Release1Sql() {
    }

    static String pgContainer() {
        String raw = System.getProperty("e2e.pgContainer");
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("e2e.pgContainer is not set - the runner passes its Postgres container name");
        }
        return raw.trim();
    }

    /** The id as a SQL literal, only if it is a UUID. */
    static String uuid(String id) {
        if (id == null || !UUID_TEXT.matcher(id).matches()) {
            throw new IllegalArgumentException("not a UUID: " + id);
        }
        return "'" + id + "'";
    }

    /** Rows of a SELECT, cells split on '|' (unaligned, no header). */
    static List<List<String>> rows(String sql) throws Exception {
        Process process = new ProcessBuilder("docker", "exec", pgContainer(), "psql", "-U", "owner", "-d", "platform",
                "-v", "ON_ERROR_STOP=1", "-At", "-F", "|", "-c", sql).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError("psql failed: " + out);
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : out.split("\\R")) {
            if (!line.isBlank()) {
                rows.add(List.of(line.split("\\|", -1)));
            }
        }
        return rows;
    }

    /**
     * One row per delivery of the message: id | state | contract version id | attempt count | outcome classes in order |
     * event types in order.
     */
    static List<List<String>> deliveriesOf(String messageId) throws Exception {
        String id = uuid(messageId);
        return rows("select d.id::text, d.state, d.contract_version_id::text,"
                + " (select count(*) from public.delivery_attempt a where a.delivery_id = d.id),"
                + " coalesce((select string_agg(a.outcome_class, ',' order by a.id) from public.delivery_attempt a where a.delivery_id = d.id), ''),"
                + " coalesce((select string_agg(e.type, ',' order by e.id) from public.delivery_event e where e.delivery_id = d.id), '')"
                + " from public.delivery d where d.message_id = " + id + " order by d.created_at, d.id");
    }
}

package io.integrationplatform.config.destination;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * An allow-list that unions the operator's startup configuration ({@code platform.config.destinations.allowed-hosts})
 * with patterns stored in {@code operator_allowed_host} (V2_4_1410). The config-based entries are immutable; the
 * DB-based entries are loaded on first use (never while the bean is constructed) and refreshed after every admin
 * write, so a restart is never needed to add or remove a destination.
 *
 * <p>The hot-path {@link #allows(String)} call is two in-memory checks — no DB query per request.
 */
public final class DbBackedAllowList extends AllowList {

    public record HostEntry(UUID id, String pattern) {}

    private static final Logger LOG = LoggerFactory.getLogger(DbBackedAllowList.class);
    private static final long RETRY_NANOS = 30_000_000_000L;

    private final AllowList configBased;
    private final JdbcClient jdbcClient;
    private volatile AllowList dbEntries;
    private volatile boolean loaded;
    private volatile boolean tried;
    private volatile long lastTryNanos;

    public DbBackedAllowList(AllowList configBased, JdbcClient jdbcClient) {
        super(List.of(), List.of());
        this.configBased = Objects.requireNonNull(configBased, "configBased");
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.dbEntries = AllowList.of(List.of());
    }

    @Override
    public boolean allows(String host) {
        if (configBased.allows(host)) {
            return true;
        }
        if (!loaded && (!tried || System.nanoTime() - lastTryNanos > RETRY_NANOS)) {
            tryLoad();
        }
        return dbEntries.allows(host);
    }

    /**
     * First-use/retry load: a database that cannot be read yet leaves the DB entries empty (fail closed: only the
     * deployment configuration is allowed) and is retried at most every 30 s; it never stops the application starting.
     */
    private void tryLoad() {
        tried = true;
        lastTryNanos = System.nanoTime();
        try {
            refresh();
        } catch (DataAccessException unreadable) {
            LOG.warn("operator_allowed_host not readable yet - allow-list holds the configured hosts only: {}",
                    unreadable.getMostSpecificCause().getMessage());
        }
    }

    /** Reload the DB patterns into the in-memory allow-list. Called after every add/remove. */
    public void refresh() {
        List<String> patterns = jdbcClient
                .sql("SELECT pattern FROM operator_allowed_host ORDER BY pattern")
                .query(String.class)
                .list();
        this.dbEntries = AllowList.of(patterns);
        this.loaded = true;
    }

    /** All DB-stored entries (the config-based entries are not included — they are deployment config). */
    public List<HostEntry> list() {
        return jdbcClient
                .sql("SELECT id, pattern FROM operator_allowed_host ORDER BY pattern")
                .query((rs, n) -> new HostEntry(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("pattern")))
                .list();
    }

    /** Insert a pattern and refresh the in-memory list. Returns the new entry. */
    public HostEntry add(String pattern) {
        String clean = normalise(pattern);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
        UUID id = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO operator_allowed_host (id, pattern) VALUES (:id, :pattern)")
                .param("id", id)
                .param("pattern", clean)
                .update();
        refresh();
        return new HostEntry(id, clean);
    }

    /** Remove a pattern by id and refresh the in-memory list. Returns true if a row was deleted. */
    public boolean remove(UUID id) {
        int rows = jdbcClient.sql("DELETE FROM operator_allowed_host WHERE id = :id")
                .param("id", id)
                .update();
        if (rows > 0) {
            refresh();
        }
        return rows > 0;
    }
}

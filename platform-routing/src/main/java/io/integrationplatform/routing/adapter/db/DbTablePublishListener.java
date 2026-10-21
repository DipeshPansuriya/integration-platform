package io.integrationplatform.routing.adapter.db;

import io.integrationplatform.config.flow.api.InterfacePublishRefused;
import io.integrationplatform.config.flow.api.InterfacePublished;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * E16 F3 (design note task 10, ADR-017 Decision 1): publishing an interface whose DB target table is not APPROVED is
 * refused through config's public {@link InterfacePublishRefused} - fail early, 400 with the system and table named
 * (both are validated plain identifiers / codes, safe to show a tenant admin). Runs inside the publish transaction just
 * before it commits, like the route listener; a lookup failure rolls the publish back (never "allowed").
 */
public final class DbTablePublishListener {

    private static final int MAX_NAMED = 5;

    private final UnapprovedTables unapproved;

    public DbTablePublishListener(UnapprovedTables unapproved) {
        this.unapproved = Objects.requireNonNull(unapproved, "unapproved");
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onInterfacePublished(InterfacePublished published) {
        List<String> missing = unapproved.ofInterface(published.interfaceId());
        if (!missing.isEmpty()) {
            String named = String.join(", ", missing.stream().limit(MAX_NAMED).toList());
            throw new InterfacePublishRefused("the publish was refused: the DB table is not approved for the target ("
                    + named + (missing.size() > MAX_NAMED ? ", ..." : "") + "); request and approve it first",
                    new IllegalStateException("unapproved DB tables: " + missing));
        }
    }
}

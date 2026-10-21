package io.integrationplatform.routing.adapter.db;

import io.integrationplatform.routing.stage2.Stage2Send;
import java.util.Optional;

/** Resolves a due delivery to its DB insert; empty means the delivery has nothing sendable (PERMANENT). */
@FunctionalInterface
public interface DbOutbound {

    Optional<DbMessage> resolve(Stage2Send send);
}
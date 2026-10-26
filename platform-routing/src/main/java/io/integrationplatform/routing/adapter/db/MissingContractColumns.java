package io.integrationplatform.routing.adapter.db;

import java.util.List;
import java.util.UUID;

/**
 * E16 F3 (design note task 9): the publish-time read of the mapped columns an interface's ACTIVE DB routes send that
 * are NOT in their approved target contract's properties. Each entry reads {@code systemCode.table.column} - all
 * three validated plain identifiers / codes, safe to show a tenant admin. Empty means every mapped column is in the
 * approved contract. An implementation THROWS when it cannot tell (the platform database is down): a publish rolls
 * back, it is never read as "all columns present".
 */
@FunctionalInterface
public interface MissingContractColumns {

    List<String> ofInterface(UUID interfaceId);
}
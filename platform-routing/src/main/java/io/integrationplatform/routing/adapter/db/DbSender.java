package io.integrationplatform.routing.adapter.db;

import java.net.InetAddress;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The seam that performs one DB send (the KafkaSender precedent): the parameterised insert with the delivery id,
 * against the target's restricted account. The real driver lives in {@code JdbcDbSender}; the controls test
 * substitutes this.
 */
@FunctionalInterface
public interface DbSender {

    /**
     * Executes the parameterised insert for this delivery. A natural {@code 08001} means the target never
     * answered; a {@code 08006} means the channel dropped after the statement may have been sent (E16-C4); a
     * constraint or privilege refusal ({@code 23}, {@code 42}) means the target refused the row.
     *
     * @param pinnedAddress the ONE address the destination guard checked for the host: the connection dials it and
     *                      nothing else (no second resolution)
     */
    void insert(DbMessage message, UUID deliveryId, InetAddress pinnedAddress) throws SQLException;
}
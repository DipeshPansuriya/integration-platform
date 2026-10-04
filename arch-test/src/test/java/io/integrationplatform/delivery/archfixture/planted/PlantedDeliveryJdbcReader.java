package io.integrationplatform.delivery.archfixture.planted;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Planted violation for E1-F2-S1, example "raw JDBC call | platform-delivery": plain JDBC with no tenant predicate (the
 * hole the deleted build's rule missed, R08). Test class only - never in the production scan.
 */
public class PlantedDeliveryJdbcReader {

    private final DataSource dataSource;

    public PlantedDeliveryJdbcReader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public int countDeliveries() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("select count(*) from delivery");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }
}

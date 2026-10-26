package io.integrationplatform.e16.archfixture.planted;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Planted violation: the class the test passes to the rule as "the exempt target sender" opens its connection from the
 * platform {@link DataSource} instead of its own driver connection. The exemption covers only the target connection's
 * statements, so the {@code DataSource.getConnection()} call must still be flagged. Test class only.
 */
public class PlantedTargetSenderOnPlatformDataSource {

    private final DataSource platformDataSource;

    public PlantedTargetSenderOnPlatformDataSource(DataSource platformDataSource) {
        this.platformDataSource = platformDataSource;
    }

    public void insert() throws SQLException {
        try (Connection connection = platformDataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("insert into t values (1)")) {
            statement.executeUpdate();
        }
    }
}

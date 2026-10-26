package io.integrationplatform.e16.archfixture.planted;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Planted violation for the target-database exemption: a DIFFERENT class making the very same JDBC calls as the
 * reviewed target sender (DriverManager connection, prepareStatement, executeUpdate). The exemption is for one exact
 * class name, so this one must still be flagged. Test class only - never in the production scan.
 */
public class PlantedTargetDbLookalike {

    public void insert(String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
                PreparedStatement statement = connection.prepareStatement("insert into t values (1)")) {
            statement.executeUpdate();
        }
    }
}

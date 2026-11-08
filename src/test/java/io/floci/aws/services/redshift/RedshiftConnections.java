package io.floci.aws.services.redshift;

import io.floci.aws.services.redshift.model.Cluster;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

public final class RedshiftConnections {

    private RedshiftConnections() {}

    public static String jdbcUrl(Cluster cluster) {
        return "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/dev";
    }

    public static Connection waitForConnection(Cluster cluster) throws SQLException {
        return waitForConnection(cluster, "admin", "Secret123");
    }

    public static Connection waitForConnection(Cluster cluster, String username, String password) throws SQLException {
        return waitForConnection(jdbcUrl(cluster), username, password);
    }

    public static Connection waitForConnection(String host, int port, String username, String password) throws SQLException {
        return waitForConnection("jdbc:postgresql://" + host + ":" + port + "/dev", username, password);
    }

    public static Connection waitForConnection(String jdbcUrl, String username, String password) throws SQLException {
        try {
            return Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .until(() -> DriverManager.getConnection(jdbcUrl, username, password), Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            return DriverManager.getConnection(jdbcUrl, username, password);
        }
    }
}

package io.github.hectorvent.floci.testing;

import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** A Docker-backed Redshift cluster for tests that need the cluster's own PostgreSQL. */
public final class RedshiftTestCluster implements AutoCloseable {

    public static final String MASTER_USER = "admin";
    public static final String PASSWORD = "Secret123";

    private final RedshiftService service;
    private final Cluster cluster;

    private RedshiftTestCluster(RedshiftService service, Cluster cluster) {
        this.service = service;
        this.cluster = cluster;
    }

    public static RedshiftTestCluster create(RedshiftService service, String prefix) {
        String id = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return new RedshiftTestCluster(service, service.createCluster(id, "dc2.large", MASTER_USER, PASSWORD));
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/dev";
    }

    /** Opens a connection as {@code user}, waiting for the cluster to accept connections first. */
    public Connection connect(String user) throws SQLException {
        return connect(user, null);
    }

    /** Opens a connection with the requested query mode, or the driver's default when it is null. */
    public Connection connect(String user, String queryMode) throws SQLException {
        String url = jdbcUrl() + (queryMode == null ? "" : "?preferQueryMode=" + queryMode);
        try {
            return Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                    .until(() -> DriverManager.getConnection(url, user, PASSWORD), Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            return DriverManager.getConnection(url, user, PASSWORD);
        }
    }

    @Override
    public void close() {
        service.deleteCluster(cluster.getClusterIdentifier());
    }
}

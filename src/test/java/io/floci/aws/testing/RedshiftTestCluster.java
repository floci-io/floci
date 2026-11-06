package io.floci.aws.testing;

import io.floci.aws.services.redshift.RedshiftConnections;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.model.Cluster;

import java.sql.Connection;
import java.sql.SQLException;
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
        return RedshiftConnections.jdbcUrl(cluster);
    }

    /** Opens a connection as {@code user}, waiting for the cluster to accept connections first. */
    public Connection connect(String user) throws SQLException {
        return connect(user, null);
    }

    /** Opens a connection with the requested query mode, or the driver's default when it is null. */
    public Connection connect(String user, String queryMode) throws SQLException {
        String url = jdbcUrl() + (queryMode == null ? "" : "?preferQueryMode=" + queryMode);
        return RedshiftConnections.waitForConnection(url, user, PASSWORD);
    }

    @Override
    public void close() {
        service.deleteCluster(cluster.getClusterIdentifier());
    }
}

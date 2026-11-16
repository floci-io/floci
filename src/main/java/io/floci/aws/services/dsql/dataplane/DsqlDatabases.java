package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.services.dsql.model.DsqlCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The database behind each cluster. Which one it is, a PostgreSQL container per cluster or a
 * server that already runs, is the configured backend's concern; this class hands every call to it.
 */
@ApplicationScoped
public class DsqlDatabases {

    private final DsqlBackend strategy;

    /**
     * Where a cluster's database answers, who Floci connects as, and how role names map.
     *
     * @param superuserPassword null when the database trusts the connection
     */
    public record Backend(String host, int port, String database, String superuser, String superuserPassword,
                          RoleNames roleNames, BackendCredentials credentials) {
        public String jdbcUrl() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + database;
        }

        /** Never shows the password, which a logged route would otherwise carry. */
        @Override
        public String toString() {
            return "Backend[host=" + host + ", port=" + port + ", database=" + database + ", superuser=" + superuser
                    + ", superuserPassword=" + (superuserPassword == null ? "null" : "***") + ", roleNames="
                    + roleNames + ", credentials=" + credentials + "]";
        }
    }

    @Inject
    public DsqlDatabases(DockerDsqlBackend docker) {
        this.strategy = docker;
    }

    /** Starts the cluster's database, waits until it answers, and sets it up as DSQL's database is. */
    public Backend start(DsqlCluster cluster) throws SQLException, IOException {
        return strategy.start(cluster);
    }

    /** Stops the cluster's database and, with {@code dropData}, removes its data. */
    public void stop(DsqlCluster cluster, boolean dropData) {
        strategy.stop(cluster, dropData);
    }

    /** A superuser connection to the cluster's database, for what the endpoint checks itself. */
    public static Connection superuser(Backend backend) throws SQLException {
        String password = backend.superuserPassword() == null ? "" : backend.superuserPassword();
        return DriverManager.getConnection(backend.jdbcUrl(), backend.superuser(), password);
    }
}

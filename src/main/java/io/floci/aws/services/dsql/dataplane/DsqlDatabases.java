package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.dsql.model.DsqlCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
    public DsqlDatabases(DockerDsqlBackend docker, ExternalDsqlBackend external, EmulatorConfig config) {
        this.strategy = switch (config.services().dsql().backend()) {
            case DOCKER -> docker;
            case EXTERNAL -> external;
        };
    }

    /** Starts the cluster's database, waits until it answers, and sets it up as DSQL's database is. */
    public Backend start(DsqlCluster cluster) throws SQLException, IOException {
        return strategy.start(cluster);
    }

    /** The backend roles that belong to the cluster alone and go with it. */
    public List<String> rolesToDrop(DsqlCluster cluster) throws SQLException {
        return strategy.rolesToDrop(cluster);
    }

    /** Stops the cluster's database and, with {@code dropData}, removes its data. */
    public void stop(DsqlCluster cluster, boolean dropData) {
        strategy.stop(cluster, dropData);
    }

    /**
     * What may stay behind when a FAILED cluster's deletion cannot clean up, if the deletion
     * finishes anyway; empty when it waits and is retried instead.
     */
    public Optional<String> leftoversOfFailed(DsqlCluster cluster) {
        return strategy.leftoversOfFailed(cluster);
    }

    /** How often {@link #awaitConnection} tries again. */
    public static final Duration READY_INTERVAL = Duration.ofMillis(250);

    /** Opens a connection to a server {@link #awaitConnection} waits for. */
    @FunctionalInterface
    public interface Connector {
        Connection open() throws SQLException;
    }

    /**
     * A connection from {@code connector}, tried again every {@code interval} until {@code timeout}
     * has passed, so a server still starting is waited for. It is tried at least once. With
     * {@code refusedLoginIsFinal}, a refused login (SQLSTATE class 28) is thrown at once, as waiting
     * would not change a wrong password; otherwise it is tried again like any other failure. Past
     * the deadline, the last failure is thrown, with its SQLSTATE, under {@code what}: for example
     * "The database did not let Floci in within 90 s: ...". Public for the tests' throwaway
     * PostgreSQL servers, which wait the same way.
     */
    public static Connection awaitConnection(Connector connector, Duration timeout, Duration interval,
                                             boolean refusedLoginIsFinal, String what) throws SQLException {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            SQLException last;
            try {
                return connector.open();
            } catch (SQLException e) {
                if (refusedLoginIsFinal && e.getSQLState() != null && e.getSQLState().startsWith("28")) {
                    throw e;
                }
                last = e;
            }
            if (Instant.now().plus(interval).isAfter(deadline)) {
                throw new SQLException(what + " did not let Floci in within " + timeout.toSeconds() + " s: "
                        + last.getMessage(), last.getSQLState(), last);
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                SQLException stopped = new SQLException("Interrupted while waiting: " + what, interrupted);
                stopped.addSuppressed(last);
                throw stopped;
            }
        }
    }

    /** A superuser connection to the cluster's database, for what the endpoint checks itself. */
    public static Connection superuser(Backend backend) throws SQLException {
        String password = backend.superuserPassword() == null ? "" : backend.superuserPassword();
        return DriverManager.getConnection(backend.jdbcUrl(), backend.superuser(), password);
    }
}

package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.dsql.model.DsqlCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

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
    }

    @Inject
    public DsqlDatabases(DockerDsqlBackend docker, EmulatorConfig config) {
        this.strategy = select(config.services().dsql().backend(), docker);
    }

    private static DsqlBackend select(String configured, DockerDsqlBackend docker) {
        EmulatorConfig.DsqlServiceConfig.Backend kind;
        try {
            kind = EmulatorConfig.DsqlServiceConfig.Backend.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("floci.services.dsql.backend must be docker or external, not '"
                    + configured + "'", e);
        }
        return switch (kind) {
            case DOCKER -> docker;
            case EXTERNAL -> new UnavailableBackend();
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

    /** A superuser connection to the cluster's database, for what the endpoint checks itself. */
    public static Connection superuser(Backend backend) throws SQLException {
        String password = backend.superuserPassword() == null ? "" : backend.superuserPassword();
        return DriverManager.getConnection(backend.jdbcUrl(), backend.superuser(), password);
    }

    /** Stands in for the external backend until it exists. */
    private static final class UnavailableBackend implements DsqlBackend {
        @Override
        public Backend start(DsqlCluster cluster) {
            throw new UnsupportedOperationException("The external DSQL backend is not implemented yet");
        }

        @Override
        public List<String> rolesToDrop(DsqlCluster cluster) {
            return List.of();
        }

        @Override
        public void stop(DsqlCluster cluster, boolean dropData) {
            throw new UnsupportedOperationException("The external DSQL backend is not implemented yet");
        }
    }
}

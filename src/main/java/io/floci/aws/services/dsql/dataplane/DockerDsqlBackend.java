package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.services.dsql.model.DsqlCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The PostgreSQL 16 container behind each cluster, set up as DSQL's database is. The data is
 * kept as RDS keeps an instance's (a Docker volume of its own, or a directory under the host
 * persistent path), so a cluster survives a restart of Floci when its storage does, and is removed
 * with the cluster under the same policy. The database trusts its connections, as ElastiCache's
 * backends do behind their proxy: its port is published on the loopback address only, or, when
 * Floci runs in Docker, reached on Floci's Docker network without being published. Clients reach
 * it through Floci's endpoint, which authenticates every one with its IAM token.
 */
@ApplicationScoped
public class DockerDsqlBackend implements DsqlBackend {

    private static final Logger LOG = Logger.getLogger(DockerDsqlBackend.class);
    private static final int PORT = 5432;
    private static final String DATABASE = "postgres";
    private static final String SUPERUSER = "dbowner";
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(90);
    private static final String DATA_PATH = "/var/lib/postgresql/data";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;

    @Inject
    public DockerDsqlBackend(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector, EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.config = config;
    }

    /** Starts the cluster's database, waits until it answers, and sets it up as DSQL's database is. */
    @Override
    public DsqlDatabases.Backend start(DsqlCluster cluster) throws SQLException, IOException {
        String name = containerName(cluster);
        Map<String, String> labels = ContainerStorageHelper.resourceIdentityLabels(
                "dsql", cluster.getIdentifier(), cluster.getAccountId(), cluster.getRegion());
        lifecycleManager.removeIfExistsStrict(name);
        ContainerBuilder.Builder spec = containerBuilder.newContainer(config.services().dsql().image())
                .withName(name)
                .withEnv(List.of("POSTGRES_USER=" + SUPERUSER, "POSTGRES_DB=" + DATABASE, "POSTGRES_HOST_AUTH_METHOD=trust"))
                .withDockerNetwork(config.services().dsql().dockerNetwork())
                .withLogRotation()
                .withLabels(labels);
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            lifecycleManager.ensureVolume(name, labels);
            spec.withNamedVolume(name, DATA_PATH);
        } else {
            String hostPath = ContainerStorageHelper.hostResourcePath(config, "dsql", cluster.getIdentifier()).toString();
            ContainerStorageHelper.ensureHostDir(containerDetector, hostPath);
            spec.withBind(hostPath, DATA_PATH);
        }
        if (containerDetector.isRunningInContainer()) {
            spec.withExposedPort(PORT);
        } else {
            spec.withLoopbackPortBinding(PORT, 0);
        }
        ContainerSpec built = spec.build();
        String containerId = lifecycleManager.create(built);
        try {
            ContainerLifecycleManager.EndpointInfo endpoint =
                    lifecycleManager.startCreated(containerId, built).getEndpoint(PORT);
            DsqlDatabases.Backend backend = new DsqlDatabases.Backend(endpoint.host(), endpoint.port(), DATABASE,
                    SUPERUSER, null, RoleNames.IDENTITY, BackendCredentials.TRUST);
            awaitReady(backend);
            bootstrap(backend);
            LOG.infov("DSQL cluster {0} serves from {1}:{2}", cluster.getIdentifier(), backend.host(),
                    String.valueOf(backend.port()));
            return backend;
        } catch (SQLException | RuntimeException e) {
            // A database that never served does not keep running: its cluster is FAILED, and deleting
            // the cluster removes its data.
            try {
                lifecycleManager.removeIfExistsStrict(name);
            } catch (RuntimeException removal) {
                e.addSuppressed(removal);
            }
            throw e;
        }
    }

    /**
     * Stops the cluster's database and, with {@code dropData}, removes its volume as Floci removes
     * a deleted resource's: always with in-memory storage, otherwise when
     * {@code prune-volumes-on-delete} is on. A host directory is left in place, as RDS leaves it.
     * Throws when Docker cannot remove either, so a deletion is not taken for done.
     */
    @Override
    public void stop(DsqlCluster cluster, boolean dropData) {
        String name = containerName(cluster);
        lifecycleManager.removeIfExistsStrict(name);
        if (dropData && ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.removeNamedVolumeStrict(config, lifecycleManager, name);
        }
    }

    private String containerName(DsqlCluster cluster) {
        return ContainerStorageHelper.dockerName(config, "dsql-" + cluster.getIdentifier());
    }

    private static void awaitReady(DsqlDatabases.Backend backend) throws SQLException {
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        SQLException last = null;
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = DsqlDatabases.superuser(backend); Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
                return;
            } catch (SQLException e) {
                last = e;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted while the database started", interrupted);
                }
            }
        }
        throw new SQLException("The database did not start within " + READY_TIMEOUT.toSeconds() + " s", last);
    }

    private static void bootstrap(DsqlDatabases.Backend backend) throws SQLException {
        String script = DsqlBootstrap.render(DATABASE, SUPERUSER, "admin");
        try (Connection connection = DsqlDatabases.superuser(backend); Statement statement = connection.createStatement()) {
            statement.execute(script);
        }
    }
}

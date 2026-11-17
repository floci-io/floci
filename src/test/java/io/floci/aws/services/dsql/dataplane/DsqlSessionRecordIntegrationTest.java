package io.floci.aws.services.dsql.dataplane;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.services.dsql.DsqlClusterFixture;
import io.floci.aws.services.dsql.DsqlDatabasesProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A login records its session while another login purges the same ended session. The cluster
 * database runs at repeatable read, where the second purge of a row the first deleted fails with a
 * serialization error and the login would be refused; the endpoint records at read committed.
 */
@QuarkusTest
@TestProfile(DsqlDatabasesProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class DsqlSessionRecordIntegrationTest {

    private static final int ENDED_PID = 2147483000;
    private static final int LOGIN_PID = 2147483001;
    private static final int PORT = 5432;

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    EmulatorConfig config;

    @Inject
    DsqlEndpoint endpoint;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int httpPort;

    private DsqlClusterFixture cluster;
    private DsqlDatabases.Backend backend;

    @BeforeAll
    void createCluster() {
        cluster = new DsqlClusterFixture(httpPort, endpoint);
        cluster.create(dockerClient);
        backend = databaseOf(cluster.identifier());
    }

    @AfterAll
    void deleteCluster() {
        cluster.delete();
    }

    @Test
    void aLoginStillRecordsItsSessionWhenAnotherPurgesTheSameEndedOne() throws Exception {
        try (Connection other = DsqlDatabases.superuser(backend); Statement statement = other.createStatement()) {
            // A session that has ended, which both the other login and this one purge.
            statement.execute("INSERT INTO sys.session_identity VALUES (" + ENDED_PID + ", 'ended', 'ended')");
            other.setAutoCommit(false);
            statement.execute("DELETE FROM sys.session_identity WHERE pid = " + ENDED_PID);

            CompletableFuture<Boolean> recorded = CompletableFuture.supplyAsync(() ->
                    endpoint.recordSession(backend, LOGIN_PID, "arn:aws:iam::111122223333:root", "s1"));
            await().atMost(Duration.ofSeconds(30)).until(() -> "1".equals(single(
                    "SELECT count(*)::text FROM pg_stat_activity WHERE wait_event_type = 'Lock'")));
            other.commit();

            assertTrue(recorded.get(30, TimeUnit.SECONDS), "the login's purge passes over the row already gone");
            assertEquals("s1", single("SELECT session_id FROM sys.session_identity WHERE pid = " + LOGIN_PID));
        }
    }

    /**
     * Where the cluster's database container answers, resolved as {@link DsqlDatabases#start} resolves
     * it: its published loopback port, or, when Floci runs in a container, its address on the DSQL
     * Docker network.
     */
    private DsqlDatabases.Backend databaseOf(String identifier) {
        List<Container> containers = dockerClient.listContainersCmd()
                .withLabelFilter(Map.of(ContainerStorageHelper.SERVICE_LABEL, "dsql",
                        ContainerStorageHelper.RESOURCE_ID_LABEL, identifier))
                .exec();
        assertEquals(1, containers.size(), "the cluster's database container");
        String network = containerBuilder.resolveDockerNetwork(config.services().dsql().dockerNetwork()).orElse(null);
        ContainerLifecycleManager.EndpointInfo address =
                lifecycleManager.resolveEndpoint(containers.get(0).getId(), PORT, network);
        return new DsqlDatabases.Backend(address.host(), address.port(), "postgres", "dbowner", null,
                RoleNames.IDENTITY, BackendCredentials.TRUST);
    }

    private String single(String sql) throws SQLException {
        try (Connection connection = DsqlDatabases.superuser(backend); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}

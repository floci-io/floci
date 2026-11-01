package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftProxyIntegrationTest {

    @Inject
    RedshiftService service;

    private String clusterId;

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            service.deleteCluster(clusterId);
        }
    }

    private static String jdbcUrl(Cluster c) {
        // Use 127.0.0.1 explicitly instead of c.getEndpoint().getAddress() to avoid UnknownHostException
        // in CI environments where floci.emulator.hostname is set to host.docker.internal.
        return "jdbc:postgresql://127.0.0.1:" + c.getEndpoint().getPort() + "/dev";
    }

    private static Connection waitForConnection(Cluster cluster, String username, String password) throws SQLException {
        try {
            return Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .until(() -> DriverManager.getConnection(jdbcUrl(cluster), username, password), Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            return DriverManager.getConnection(jdbcUrl(cluster), username, password); // throw original
        }
    }

    @Test
    void roundTripsSqlThroughTheAdvertisedEndpoint() throws SQLException {
        clusterId = "it-proxy-roundtrip";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection conn = waitForConnection(cluster, "admin", "Secret123");
            Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE people (name text)");
            st.execute("INSERT INTO people VALUES ('Alice')");
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM people")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void pauseResumePreservesSqlAndProxyAuthentication() throws SQLException {
        clusterId = "it-pause-resume";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        int port = cluster.getEndpoint().getPort();
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE pause_data (value integer)");
            statement.execute("INSERT INTO pause_data VALUES (42)");
            statement.execute("CREATE VIEW pause_catalog_view AS SELECT * FROM pg_table_def");
            assertEquals("paused", service.pauseCluster(clusterId).getClusterStatus());
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 1"));
        }
        assertThrows(SQLException.class,
                () -> DriverManager.getConnection(jdbcUrl(cluster), "admin", "Secret123"));
        assertEquals("available", service.resumeCluster(clusterId).getClusterStatus());
        assertEquals(port, cluster.getEndpoint().getPort());
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT value FROM pause_data")) {
            assertTrue(result.next());
            assertEquals(42, result.getInt(1));
        }
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM pause_catalog_view")) {
            assertTrue(result.next());
        }
        assertThrows(SQLException.class,
                () -> DriverManager.getConnection(jdbcUrl(cluster), "admin", "wrong-password"));
    }

    @Test
    void rejectsAWrongPasswordAtTheProxy() throws SQLException {
        clusterId = "it-proxy-badpass";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        // Ensure it's ready first
        try (Connection conn = waitForConnection(cluster, "admin", "Secret123")) {
            assertTrue(conn.isValid(5));
        }

        assertThrows(SQLException.class, () ->
                DriverManager.getConnection(jdbcUrl(cluster), "admin", "wrong-password"));
    }

    @Test
    void reflectsAPasswordChangeForNewConnections() throws SQLException {
        clusterId = "it-proxy-rotate";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        // Ensure it's ready before testing rotation
        try (Connection conn = waitForConnection(cluster, "admin", "Secret123")) {
            assertTrue(conn.isValid(5));
        }

        service.modifyCluster(clusterId, null, null, "Rotated123", null, null);

        assertThrows(SQLException.class, () ->
                DriverManager.getConnection(jdbcUrl(cluster), "admin", "Secret123"));
        try (Connection conn = DriverManager.getConnection(jdbcUrl(cluster), "admin", "Rotated123")) {
            assertTrue(conn.isValid(5));
        }
    }

    @Test
    void keepsTheEndpointStableAndDataIntactAcrossAReboot() throws SQLException {
        clusterId = "it-proxy-reboot";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        int portBefore = cluster.getEndpoint().getPort();

        try (Connection conn = waitForConnection(cluster, "admin", "Secret123");
            Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (n int)");
            st.execute("INSERT INTO t VALUES (42)");
        }

        Cluster rebooted = service.rebootCluster(clusterId);
        assertEquals(portBefore, rebooted.getEndpoint().getPort());

        try (Connection conn = waitForConnection(rebooted, "admin", "Secret123");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT n FROM t")) {
            assertTrue(rs.next());
            assertEquals(42, rs.getInt(1));
        }
    }
}

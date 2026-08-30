package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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
        return "jdbc:postgresql://" + c.getEndpoint().getAddress() + ":" + c.getEndpoint().getPort() + "/dev";
    }

    @Test
    void roundTripsSqlThroughTheAdvertisedEndpoint() throws SQLException {
        clusterId = "it-proxy-roundtrip";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection conn = DriverManager.getConnection(jdbcUrl(cluster), "admin", "Secret123");
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
    void rejectsAWrongPasswordAtTheProxy() {
        clusterId = "it-proxy-badpass";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        assertThrows(SQLException.class, () ->
                DriverManager.getConnection(jdbcUrl(cluster), "admin", "wrong-password"));
    }

    @Test
    void reflectsAPasswordChangeForNewConnections() throws SQLException {
        clusterId = "it-proxy-rotate";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

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

        try (Connection conn = DriverManager.getConnection(jdbcUrl(cluster), "admin", "Secret123");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (n int)");
            st.execute("INSERT INTO t VALUES (42)");
        }

        Cluster rebooted = service.rebootCluster(clusterId);
        assertEquals(portBefore, rebooted.getEndpoint().getPort());

        try (Connection conn = DriverManager.getConnection(jdbcUrl(rebooted), "admin", "Secret123");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT n FROM t")) {
            assertTrue(rs.next());
            assertEquals(42, rs.getInt(1));
        }
    }
}

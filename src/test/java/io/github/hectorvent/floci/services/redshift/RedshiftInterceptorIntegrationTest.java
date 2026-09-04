package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftInterceptorIntegrationTest {

    @Inject
    RedshiftService service;

    private String clusterId;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for Redshift interceptor integration tests");
    }

    private static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            int exit = process.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            service.deleteCluster(clusterId);
        }
    }

    private static String jdbcUrl(Cluster c) {
        // Use 127.0.0.1 explicitly instead of c.getEndpoint().getAddress() to avoid UnknownHostException
        // in CI environments where floci.emulator.hostname is set to host.docker.internal.
        // preferQueryMode=simple forces pgjdbc to use the simple query protocol ('Q' messages)
        // rather than extended query protocol ('P'/'B'/'E'/'S' messages).
        return "jdbc:postgresql://127.0.0.1:" + c.getEndpoint().getPort() + "/dev?preferQueryMode=simple";
    }

    private static Connection waitForConnection(Cluster cluster, String username, String password) throws SQLException {
        try {
            return Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .until(() -> DriverManager.getConnection(jdbcUrl(cluster), username, password), Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            return DriverManager.getConnection(jdbcUrl(cluster), username, password); // throw original
        }
    }

    @Test
    void rewritesCreateTableDdlOverSimpleQueryProtocol() throws SQLException {
        clusterId = "it-interceptor-create";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection conn = waitForConnection(cluster, "admin", "Secret123");
            Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE sales (id int ENCODE az64, d date) DISTSTYLE KEY DISTKEY (id) COMPOUND SORTKEY (d);");
            st.execute("INSERT INTO sales VALUES (1, '2026-01-01');");
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM sales")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void rewritesAlterTableDdlOverSimpleQueryProtocol() throws SQLException {
        clusterId = "it-interceptor-alter";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection conn = waitForConnection(cluster, "admin", "Secret123");
            Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (id int)");
            st.execute("ALTER TABLE t ADD COLUMN note varchar(20) ENCODE lzo;");
            st.execute("INSERT INTO t VALUES (1, 'test-note');");
            try (ResultSet rs = st.executeQuery("SELECT id, note FROM t WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("test-note", rs.getString(2));
            }
        }
    }
}

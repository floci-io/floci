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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftCopyJobIntegrationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/LoadRole";
    private static final String COPY = "COPY public.events FROM 's3://copy-job-bucket/staging/' IAM_ROLE '" + ROLE + "'";

    @Inject
    RedshiftService service;

    private String clusterId;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for Redshift copy job tests");
    }

    private static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
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

    private static Connection waitForConnection(Cluster cluster) throws SQLException {
        String url = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/dev";
        try {
            return Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                    .until(() -> DriverManager.getConnection(url, "admin", "Secret123"), Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            return DriverManager.getConnection(url, "admin", "Secret123");
        }
    }

    @Test
    void createListShowAlterAndDropACopyJob() throws Exception {
        clusterId = "copy-job-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection connection = waitForConnection(cluster); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.events (id integer)");
            statement.execute(COPY + " JOB CREATE my_copy_job AUTO ON");

            try (ResultSet rows = statement.executeQuery("COPY JOB LIST")) {
                assertTrue(rows.next());
                assertEquals("my_copy_job", rows.getString("job_name").trim());
                assertEquals(ROLE, rows.getString("iam_role").trim());
                assertEquals(1, rows.getInt("is_auto"));
                assertFalse(rows.next());
            }

            statement.execute("COPY JOB ALTER my_copy_job AUTO OFF");
            try (ResultSet rows = statement.executeQuery("COPY JOB SHOW my_copy_job")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt("is_auto"));
            }
            try (ResultSet rows = statement.executeQuery("SELECT job_name FROM sys_copy_job")) {
                assertTrue(rows.next());
            }

            SQLException duplicate = assertThrows(SQLException.class,
                    () -> statement.execute(COPY + " JOB CREATE my_copy_job"));
            assertEquals("42710", duplicate.getSQLState());
            SQLException tolerant = assertThrows(SQLException.class,
                    () -> statement.execute(COPY + " MAXERROR 5 JOB CREATE other_job"));
            assertEquals("0A000", tolerant.getSQLState());

            statement.execute("COPY JOB DROP my_copy_job");
            try (ResultSet rows = statement.executeQuery("COPY JOB LIST")) {
                assertFalse(rows.next());
            }
            SQLException missing = assertThrows(SQLException.class,
                    () -> statement.execute("COPY JOB DROP my_copy_job"));
            assertEquals("42704", missing.getSQLState());
        }
    }
}

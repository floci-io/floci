package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RedshiftMaxErrorPostgres18IntegrationTest.Postgres18Profile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedshiftMaxErrorPostgres18IntegrationTest {

    public static class Postgres18Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.redshift.image-version", "postgres:18-alpine");
        }
    }

    private static final String CLUSTER_ID = "it-maxerror-pg18";
    private static final String BUCKET = "it-maxerror-pg18-bucket";

    @Inject
    RedshiftService service;

    @Inject
    RedshiftSnapshotScheduler snapshotScheduler;

    @Inject
    S3Service s3;

    private Cluster cluster;

    @BeforeAll
    void createCluster() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for Redshift Docker integration tests");
        cluster = service.createCluster(CLUSTER_ID, "dc2.large", "admin", "Secret123");
        s3.createBucket(BUCKET, "us-east-1");
    }

    @AfterAll
    void deleteCluster() {
        if (cluster != null) {
            service.deleteCluster(CLUSTER_ID);
            snapshotScheduler.tick(Instant.now());
        }
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

    private static String jdbcUrl(Cluster cluster) {
        return "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/dev";
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
            return DriverManager.getConnection(jdbcUrl(cluster), username, password);
        }
    }

    @Test
    void maxError2LoadsGoodRowsPastOneBadValueAndFailsOnSecond() throws Exception {
        s3.putObject(BUCKET, "one-error.txt",
                "1|alice\nnot_a_number|bob\n2|charlie\n".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());
        s3.putObject(BUCKET, "two-errors.txt",
                "1|alice\nbad_1|bob\nbad_2|charlie\n2|david\n".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());

        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
                Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE t_maxerror_two (id int, name text)");

            // MAXERROR 2 tolerates 1 conversion error (REJECT_LIMIT 1) and loads the good rows past it
            stmt.execute("COPY t_maxerror_two (id, name) FROM 's3://" + BUCKET + "/one-error.txt' "
                    + "DELIMITER '|' MAXERROR 2");

            try (ResultSet rs = stmt.executeQuery("SELECT count(*), min(id), max(id) FROM t_maxerror_two")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertEquals(1, rs.getInt(2));
                assertEquals(2, rs.getInt(3));
            }

            // A second bad value reaches the error count of 2 and aborts the COPY
            stmt.execute("CREATE TABLE t_maxerror_fail (id int, name text)");
            assertThrows(SQLException.class, () -> stmt.execute(
                    "COPY t_maxerror_fail (id, name) FROM 's3://" + BUCKET + "/two-errors.txt' "
                            + "DELIMITER '|' MAXERROR 2"));

            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM t_maxerror_fail")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void maxError0And1FailAtFirstConversionError() throws Exception {
        s3.putObject(BUCKET, "one-error-default.txt",
                "1|alice\nnot_a_number|bob\n2|charlie\n".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());

        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
                Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE t_maxerror_default (id int, name text)");

            // MAXERROR 0 fails at the first error
            assertThrows(SQLException.class, () -> stmt.execute(
                    "COPY t_maxerror_default (id, name) FROM 's3://" + BUCKET + "/one-error-default.txt' "
                            + "DELIMITER '|' MAXERROR 0"));

            // MAXERROR 1 also fails at the first error
            assertThrows(SQLException.class, () -> stmt.execute(
                    "COPY t_maxerror_default (id, name) FROM 's3://" + BUCKET + "/one-error-default.txt' "
                            + "DELIMITER '|' MAXERROR 1"));

            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM t_maxerror_default")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }
}

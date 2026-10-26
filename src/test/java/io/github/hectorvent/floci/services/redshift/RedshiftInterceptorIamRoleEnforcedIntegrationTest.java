package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.S3EnforceAuthProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers COPY IAM_ROLE authorization when {@code FLOCI_SERVICES_S3_ENFORCE_AUTH} is on, so the
 * role's identity-based policy actually gates S3 access. Kept in its own {@code @QuarkusTest}
 * class (running under {@link S3EnforceAuthProfile}) because Quarkus restarts the application per
 * distinct test profile; {@link RedshiftInterceptorIntegrationTest} runs with enforcement off.
 */
@QuarkusTest
@TestProfile(S3EnforceAuthProfile.class)
class RedshiftInterceptorIamRoleEnforcedIntegrationTest {

    private static final String REDSHIFT_TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}
            """;

    @Inject
    RedshiftService service;

    @Inject
    S3Service s3;

    @Inject
    IamService iamService;

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
        return waitForConnection(cluster, "extended");
    }

    private static Connection waitForConnection(Cluster cluster, String queryMode) throws SQLException {
        String url = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort()
                + "/dev?preferQueryMode=" + queryMode;
        return Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(() -> DriverManager.getConnection(url, "admin", "Secret123"), Objects::nonNull);
    }

    @Test
    void defaultRoleCopyAndUnloadEnforcePermissionsInBothQueryModes() throws Exception {
        clusterId = "it-default-iam-role";
        String bucket = "redshift-default-iam-role";
        String role = "arn:aws:iam::000000000000:role/DefaultTransferRole";
        String denied = "arn:aws:iam::000000000000:role/DefaultDeniedRole";
        s3.createBucket(bucket, "us-east-1");
        s3.putObject(bucket, "input.txt", "1|alice\n".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
        iamService.createRole("DefaultTransferRole", "/", REDSHIFT_TRUST_POLICY, null, 0, null);
        iamService.createRole("DefaultDeniedRole", "/", REDSHIFT_TRUST_POLICY, null, 0, null);
        iamService.putRolePolicy("DefaultTransferRole", "S3", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:*","Resource":"*"}
                ]}
                """);
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123", null, List.of(),
                List.of(role, denied), role);
        for (String queryMode : List.of("simple", "extended")) {
            String table = "people_" + queryMode;
            try (Connection connection = waitForConnection(cluster, queryMode);
                    Statement ddl = connection.createStatement()) {
                ddl.execute("CREATE TABLE " + table + " (id int, name text)");
                try (PreparedStatement copy = connection.prepareStatement(
                        "COPY " + table + " FROM 's3://" + bucket + "/input.txt' IAM_ROLE default")) {
                    copy.execute();
                }
                try (ResultSet rows = ddl.executeQuery("SELECT id, name FROM " + table)) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                    assertEquals("alice", rows.getString(2));
                }
                try (PreparedStatement unload = connection.prepareStatement(
                        "UNLOAD ('SELECT id, name FROM " + table + "') TO 's3://" + bucket + "/"
                                + queryMode + "/' IAM_ROLE default")) {
                    unload.execute();
                }
                assertEquals(1, s3.listObjects(bucket, queryMode + "/", null, 100).size());
            }
        }
        service.modifyClusterIamRoles(clusterId, List.of(), List.of(), denied);
        for (String queryMode : List.of("simple", "extended")) {
            try (Connection connection = waitForConnection(cluster, queryMode);
                    Statement statement = connection.createStatement()) {
                SQLException error = assertThrows(SQLException.class, () -> statement.execute(
                        "COPY people_simple FROM 's3://" + bucket + "/input.txt' IAM_ROLE default"));
                assertEquals("42501", error.getSQLState());
                SQLException unloadError = assertThrows(SQLException.class, () -> statement.execute(
                        "UNLOAD ('SELECT 1') TO 's3://" + bucket + "/denied/' IAM_ROLE default"));
                assertEquals("42501", unloadError.getSQLState());
            }
        }
        service.modifyClusterIamRoles(clusterId, List.of(), List.of(denied));
        try (Connection connection = waitForConnection(cluster);
                Statement statement = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class, () -> statement.execute(
                    "COPY people_simple FROM 's3://" + bucket + "/input.txt' IAM_ROLE default"));
            assertEquals("42501", error.getSQLState());
            assertTrue(error.getMessage().contains("No default IAM role"));
        }
    }

    @Test
    void copyWithAllowingRolePolicySucceeds() throws Exception {
        clusterId = "it-copy-iam-role-allow";
        String bucket = "redshift-iam-role-allow";
        s3.createBucket(bucket, "us-east-1");
        s3.putObject(bucket, "p1.txt", "1|alice\n".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
        iamService.createRole("CopyRoleAllow", "/", REDSHIFT_TRUST_POLICY, null, 0, null);
        iamService.putRolePolicy("CopyRoleAllow", "AllowS3", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:GetObject","s3:ListBucket"],"Resource":"*"}
                ]}
                """);
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123", null, List.of(),
                List.of("arn:aws:iam::000000000000:role/CopyRoleAllow"));

        try (Connection connection = waitForConnection(cluster);
                Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE TABLE people (id int, name text)");
            try (PreparedStatement copy = connection.prepareStatement(
                    "COPY people FROM 's3://" + bucket + "/p1.txt' "
                            + "IAM_ROLE 'arn:aws:iam::000000000000:role/CopyRoleAllow'")) {
                copy.execute();
            }
        }
    }

    @Test
    void copyWithNoMatchingRolePolicyFails() throws Exception {
        clusterId = "it-copy-iam-role-deny";
        String bucket = "redshift-iam-role-deny";
        s3.createBucket(bucket, "us-east-1");
        s3.putObject(bucket, "p1.txt", "1|alice\n".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
        iamService.createRole("CopyRoleDeny", "/", REDSHIFT_TRUST_POLICY, null, 0, null);
        // No policy attached: implicit deny.
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123", null, List.of(),
                List.of("arn:aws:iam::000000000000:role/CopyRoleDeny"));

        try (Connection connection = waitForConnection(cluster);
                Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE TABLE people (id int, name text)");
            try (PreparedStatement copy = connection.prepareStatement(
                    "COPY people FROM 's3://" + bucket + "/p1.txt' "
                            + "IAM_ROLE 'arn:aws:iam::000000000000:role/CopyRoleDeny'")) {
                assertThrows(SQLException.class, copy::execute);
            }
        }
    }
}

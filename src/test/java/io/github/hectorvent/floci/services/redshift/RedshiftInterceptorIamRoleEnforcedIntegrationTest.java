package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers COPY IAM_ROLE authorization when {@code FLOCI_SERVICES_S3_ENFORCE_AUTH} is on, so the
 * role's identity-based policy actually gates S3 access. Kept in its own {@code @QuarkusTest}
 * class (own {@link EnforceAuthProfile}) because Quarkus restarts the application per distinct
 * test profile; {@link RedshiftInterceptorIntegrationTest} runs with enforcement off.
 */
@QuarkusTest
@TestProfile(RedshiftInterceptorIamRoleEnforcedIntegrationTest.EnforceAuthProfile.class)
class RedshiftInterceptorIamRoleEnforcedIntegrationTest {

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
        String url = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/dev";
        return Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(() -> DriverManager.getConnection(url, "admin", "Secret123"), Objects::nonNull);
    }

    @Test
    void copyWithAllowingRolePolicySucceeds() throws Exception {
        clusterId = "it-copy-iam-role-allow";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        String bucket = "redshift-iam-role-allow";
        s3.createBucket(bucket, "us-east-1");
        s3.putObject(bucket, "p1.txt", "1|alice\n".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
        iamService.createRole("CopyRoleAllow", "/", "{}", null, 0, null);
        iamService.putRolePolicy("CopyRoleAllow", "AllowS3", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:GetObject","s3:ListBucket"],"Resource":"*"}
                ]}
                """);

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
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        String bucket = "redshift-iam-role-deny";
        s3.createBucket(bucket, "us-east-1");
        s3.putObject(bucket, "p1.txt", "1|alice\n".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
        iamService.createRole("CopyRoleDeny", "/", "{}", null, 0, null);
        // No policy attached: implicit deny.

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

    public static final class EnforceAuthProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.s3.enforce-auth", "true");
        }
    }
}

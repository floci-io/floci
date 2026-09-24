package io.github.hectorvent.floci.compat;

import com.floci.test.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.Cluster;
import software.amazon.awssdk.services.redshift.model.CreateClusterRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterRequest;
import software.amazon.awssdk.services.redshift.model.DescribeClustersRequest;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.CreateDatabaseRequest;
import software.amazon.awssdk.services.glue.model.DatabaseInput;
import software.amazon.awssdk.services.glue.model.DeleteDatabaseRequest;
import software.amazon.awssdk.services.glue.model.DeleteTableRequest;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.DeleteRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.DeleteRoleRequest;
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftSpectrumTest {
    private static final String ROLE_PREFIX = "SpectrumCompatRole";

    @Test
    @DisplayName("queries an S3 CSV external table through the Redshift JDBC wire")
    void queriesCsvExternalTable() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String clusterId = "spectrum-" + suffix;
        String bucket = "spectrum-" + suffix;
        RedshiftClient redshift = TestFixtures.redshiftClient();
        S3Client s3 = TestFixtures.s3Client();
        GlueClient glue = TestFixtures.glueClient();
        IamClient iam = TestFixtures.iamClient();
        boolean bucketCreated = false;
        boolean clusterCreated = false;
        String glueDatabase = "spectrum_" + suffix;
        String roleName = ROLE_PREFIX + suffix;
        String roleArn = "arn:aws:iam::000000000000:role/" + roleName;

        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            bucketCreated = true;
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key("events/part-1.csv")
                    .contentType("text/csv").build(),
                    RequestBody.fromString("id,name\n1,Alice\n2,Bob\n"));
            iam.createRole(CreateRoleRequest.builder().roleName(roleName).path("/")
                    .assumeRolePolicyDocument("""
                            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                            "Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}""")
                    .build());
            iam.putRolePolicy(PutRolePolicyRequest.builder().roleName(roleName).policyName("SpectrumS3")
                    .policyDocument("""
                            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                            "Action":["s3:GetObject","s3:ListBucket"],"Resource":"*"}]}""")
                    .build());
            glue.createDatabase(CreateDatabaseRequest.builder().databaseInput(DatabaseInput.builder()
                    .name(glueDatabase).build()).build());
            redshift.createCluster(CreateClusterRequest.builder().clusterIdentifier(clusterId)
                    .nodeType("dc2.large").masterUsername("admin").masterUserPassword("Password123")
                    .iamRoles(roleArn)
                    .build());
            clusterCreated = true;

            Cluster cluster = waitForCluster(redshift, clusterId);
            String jdbcUrl = "jdbc:postgresql://" + cluster.endpoint().address() + ":"
                    + cluster.endpoint().port() + "/dev";
            try (Connection connection = waitForConnection(jdbcUrl);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE EXTERNAL SCHEMA analytics FROM DATA CATALOG DATABASE '" + glueDatabase
                        + "' IAM_ROLE '" + roleArn + "'");
                statement.execute("CREATE EXTERNAL TABLE analytics.events (id INTEGER, name VARCHAR) "
                        + "STORED AS TEXTFILE LOCATION 's3://" + bucket + "/events/' "
                        + "TBLPROPERTIES ('skip.header.line.count'='1')");
                try (ResultSet rows = statement.executeQuery("SELECT id, name FROM analytics.events")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt("id"));
                    assertEquals("Alice", rows.getString("name"));
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt("id"));
                    assertEquals("Bob", rows.getString("name"));
                    assertTrue(!rows.next());
                }
                try (ResultSet catalog = statement.executeQuery(
                        "SELECT tablename FROM svv_external_tables WHERE schemaname = 'analytics'")) {
                    assertTrue(catalog.next());
                    assertEquals("events", catalog.getString(1));
                }
            }
        } finally {
            try {
                if (!clusterCreated) {
                    return;
                }
                redshift.deleteCluster(DeleteClusterRequest.builder().clusterIdentifier(clusterId).build());
            } finally {
                try {
                    glue.deleteTable(DeleteTableRequest.builder().databaseName(glueDatabase).name("events").build());
                    glue.deleteDatabase(DeleteDatabaseRequest.builder().name(glueDatabase).build());
                } catch (RuntimeException ignored) {
                    // Cleanup is best effort if catalog setup failed partway through.
                }
                try {
                    iam.deleteRolePolicy(DeleteRolePolicyRequest.builder().roleName(roleName)
                            .policyName("SpectrumS3").build());
                    iam.deleteRole(DeleteRoleRequest.builder().roleName(roleName).build());
                } catch (RuntimeException ignored) {
                    // Cleanup is best effort if role setup failed partway through.
                }
                if (bucketCreated) {
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key("events/part-1.csv").build());
                    s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
                }
                redshift.close();
                s3.close();
                glue.close();
                iam.close();
            }
        }
    }

    private static Cluster waitForCluster(RedshiftClient redshift, String clusterId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            try {
                Cluster cluster = redshift.describeClusters(DescribeClustersRequest.builder()
                        .clusterIdentifier(clusterId).build()).clusters().get(0);
                if (cluster.endpoint() != null && cluster.endpoint().port() > 0) {
                    return cluster;
                }
            } catch (RuntimeException ignored) {
                // The control-plane response may briefly precede endpoint publication.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Timed out waiting for Redshift cluster endpoint");
    }

    private static Connection waitForConnection(String jdbcUrl) throws InterruptedException, SQLException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        SQLException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                Connection connection = DriverManager.getConnection(jdbcUrl, "admin", "Password123");
                if (connection.isValid(5)) {
                    return connection;
                }
                connection.close();
            } catch (SQLException exception) {
                last = exception;
            }
            Thread.sleep(500);
        }
        throw last == null ? new SQLException("Timed out waiting for Redshift JDBC connection") : last;
    }
}

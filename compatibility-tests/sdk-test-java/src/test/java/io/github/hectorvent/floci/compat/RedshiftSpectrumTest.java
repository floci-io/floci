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

    @Test
    @DisplayName("queries an S3 CSV external table through the Redshift JDBC wire")
    void queriesCsvExternalTable() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String clusterId = "spectrum-" + suffix;
        String bucket = "spectrum-" + suffix;
        RedshiftClient redshift = TestFixtures.redshiftClient();
        S3Client s3 = TestFixtures.s3Client();
        boolean bucketCreated = false;
        boolean clusterCreated = false;

        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            bucketCreated = true;
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key("events/part-1.csv")
                    .contentType("text/csv").build(),
                    RequestBody.fromString("id,name\n1,Alice\n2,Bob\n"));
            redshift.createCluster(CreateClusterRequest.builder().clusterIdentifier(clusterId)
                    .nodeType("dc2.large").masterUsername("admin").masterUserPassword("Password123")
                    .build());
            clusterCreated = true;

            Cluster cluster = waitForCluster(redshift, clusterId);
            String jdbcUrl = "jdbc:postgresql://" + cluster.endpoint().address() + ":"
                    + cluster.endpoint().port() + "/dev";
            try (Connection connection = waitForConnection(jdbcUrl);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE EXTERNAL SCHEMA analytics FROM DATA CATALOG DATABASE 'dev' IAM_ROLE 'role'");
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
            }
        } finally {
            try {
                if (!clusterCreated) {
                    return;
                }
                redshift.deleteCluster(DeleteClusterRequest.builder().clusterIdentifier(clusterId).build());
            } finally {
                if (bucketCreated) {
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key("events/part-1.csv").build());
                    s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
                }
                redshift.close();
                s3.close();
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

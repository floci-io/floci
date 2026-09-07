package io.github.hectorvent.floci.compat;

import com.floci.test.TestFixtures;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.Cluster;
import software.amazon.awssdk.services.redshift.model.CreateClusterRequest;
import software.amazon.awssdk.services.redshift.model.CreateClusterResponse;
import software.amazon.awssdk.services.redshift.model.DescribeClustersRequest;
import software.amazon.awssdk.services.redshift.model.DescribeClustersResponse;
import software.amazon.awssdk.services.redshift.model.DeleteClusterRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RedshiftTest {

    private RedshiftClient getClient() {
        return TestFixtures.redshiftClient();
    }

    @Test
    @Order(1)
    public void testCreateCluster() throws Exception {
        RedshiftClient client = getClient();
        CreateClusterResponse res = client.createCluster(CreateClusterRequest.builder()
                .clusterIdentifier("test-cluster")
                .nodeType("dc2.large")
                .masterUsername("admin")
                .masterUserPassword("Password123")
                .build());
        
        assertEquals("test-cluster", res.cluster().clusterIdentifier());
        
        DescribeClustersResponse describeRes = client.describeClusters(DescribeClustersRequest.builder()
                .clusterIdentifier("test-cluster")
                .build());
                
        Cluster cluster = describeRes.clusters().get(0);
        assertEquals("test-cluster", cluster.clusterIdentifier());
        // Terraform's AWS provider polls these two on create and validates them on read (issue #3098).
        assertEquals("Available", cluster.clusterAvailabilityStatus());
        assertEquals("disabled", cluster.availabilityZoneRelocationStatus());
        assertNotNull(cluster.endpoint());
        String address = cluster.endpoint().address();
        int port = cluster.endpoint().port();
        String jdbcUrl = "jdbc:postgresql://" + address + ":" + port + "/dev";
        try (java.sql.Connection conn =
                     java.sql.DriverManager.getConnection(jdbcUrl, "admin", "Password123")) {
            assertTrue(conn.isValid(5));
        }
    }

    @Test
    @Order(2)
    public void testUnloadOverSimpleQueryWritesToS3() throws Exception {
        RedshiftClient client = getClient();
        Cluster cluster = client.describeClusters(DescribeClustersRequest.builder()
                .clusterIdentifier("test-cluster")
                .build()).clusters().get(0);
        String jdbcUrl = "jdbc:postgresql://" + cluster.endpoint().address() + ":"
                + cluster.endpoint().port() + "/dev?preferQueryMode=simple";

        S3Client s3 = TestFixtures.s3Client();
        String bucket = "redshift-unload-compat";
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());

        try (Connection conn = DriverManager.getConnection(jdbcUrl, "admin", "Password123");
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS unload_src");
            st.execute("CREATE TABLE unload_src (id int, name text)");
            st.execute("INSERT INTO unload_src VALUES (1, 'alice'), (2, 'bob')");
            st.execute("UNLOAD ('select id, name from unload_src order by id') "
                    + "TO 's3://redshift-unload-compat/out/'");
        }

        ListObjectsV2Response listing = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix("out/")
                .build());
        assertTrue(listing.keyCount() >= 1, "UNLOAD must write at least one object");

        StringBuilder all = new StringBuilder();
        listing.contents().stream()
                .sorted((a, b) -> a.key().compareTo(b.key()))
                .forEach(obj -> all.append(new String(
                        s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(obj.key()).build())
                                .asByteArray(),
                        StandardCharsets.UTF_8)));
        assertEquals("1|alice\n2|bob\n", all.toString());
    }

    @Test
    @Order(3)
    public void testDeleteCluster() {
        RedshiftClient client = getClient();
        DeleteClusterResponse res = client.deleteCluster(DeleteClusterRequest.builder()
                .clusterIdentifier("test-cluster")
                .build());
        assertEquals("test-cluster", res.cluster().clusterIdentifier());
        assertEquals("deleting", res.cluster().clusterStatus());
    }
}

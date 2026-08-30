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
    public void testDeleteCluster() {
        RedshiftClient client = getClient();
        DeleteClusterResponse res = client.deleteCluster(DeleteClusterRequest.builder()
                .clusterIdentifier("test-cluster")
                .build());
        assertEquals("test-cluster", res.cluster().clusterIdentifier());
        assertEquals("deleting", res.cluster().clusterStatus());
    }
}

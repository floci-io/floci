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
        // No JDBC connection here: the endpoint carries the backing container's own host/port
        // (Redshift has no RDS-style auth proxy yet), which isn't reachable from the harness.
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

package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.github.hectorvent.floci.services.eks.model.NodegroupStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class EksServiceTest {

    private EksService eksService;
    private EmulatorConfig config;
    private EksClusterManager clusterManager;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        // Return a distinct backend per create() call so the cluster and node-group stores
        // don't share one instance (which would mix types and break node-group scans).
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> new InMemoryStorage<>());

        config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var eksConfig = Mockito.mock(EmulatorConfig.EksServiceConfig.class);

        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.eks()).thenReturn(eksConfig);
        when(eksConfig.mock()).thenReturn(true);
        when(eksConfig.apiServerBasePort()).thenReturn(6500);
        when(config.defaultRegion()).thenReturn("us-east-1");

        clusterManager = Mockito.mock(EksClusterManager.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        eksService = new EksService(storageFactory, config, regionResolver, clusterManager);
    }

    @Test
    void createCluster() {
        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("test-cluster");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setVersion("1.29");

        Cluster cluster = eksService.createCluster(req);

        assertNotNull(cluster);
        assertEquals("test-cluster", cluster.getName());
        assertEquals(ClusterStatus.ACTIVE, cluster.getStatus());
        assertTrue(cluster.getArn().contains("test-cluster"));
        assertEquals("1.29", cluster.getVersion());
        assertNotNull(cluster.getCreatedAt());
    }

    @Test
    void createClusterDuplicateFails() {
        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("dup-cluster");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");

        eksService.createCluster(req);

        assertThrows(AwsException.class, () -> eksService.createCluster(req));
    }

    @Test
    void describeCluster() {
        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("my-cluster");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        eksService.createCluster(req);

        Cluster described = eksService.describeCluster("my-cluster");
        assertEquals("my-cluster", described.getName());
    }

    @Test
    void describeClusterNotFound() {
        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.describeCluster("nonexistent"));
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void listClusters() {
        CreateClusterRequest req1 = new CreateClusterRequest();
        req1.setName("cluster-a");
        req1.setRoleArn("arn:aws:iam::000000000000:role/eks-role");

        CreateClusterRequest req2 = new CreateClusterRequest();
        req2.setName("cluster-b");
        req2.setRoleArn("arn:aws:iam::000000000000:role/eks-role");

        eksService.createCluster(req1);
        eksService.createCluster(req2);

        List<String> names = eksService.listClusters();
        assertEquals(2, names.size());
        assertTrue(names.contains("cluster-a"));
        assertTrue(names.contains("cluster-b"));
    }

    @Test
    void deleteCluster() {
        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("to-delete");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        eksService.createCluster(req);

        Cluster deleted = eksService.deleteCluster("to-delete");
        assertEquals(ClusterStatus.DELETING, deleted.getStatus());
        assertTrue(eksService.listClusters().isEmpty());
    }

    @Test
    void taggingOperations() {
        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("tagged-cluster");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        Cluster cluster = eksService.createCluster(req);

        String arn = cluster.getArn();

        // tagResource
        eksService.tagResource(arn, Map.of("env", "test", "team", "platform"));
        Map<String, String> tags = eksService.listTagsForResource(arn);
        assertEquals("test", tags.get("env"));
        assertEquals("platform", tags.get("team"));

        // untagResource
        eksService.untagResource(arn, List.of("env"));
        tags = eksService.listTagsForResource(arn);
        assertFalse(tags.containsKey("env"));
        assertEquals("platform", tags.get("team"));
    }

    // ──────────────────────────── Managed node groups (#1137) ────────────────────────────

    private Cluster newCluster(String name) {
        CreateClusterRequest req = new CreateClusterRequest();
        req.setName(name);
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setVersion("1.29");
        return eksService.createCluster(req);
    }

    private Nodegroup newNodegroupRequest(String name) {
        Nodegroup ng = new Nodegroup();
        ng.setNodegroupName(name);
        ng.setSubnets(List.of("subnet-123"));
        ng.setNodeRole("arn:aws:iam::000000000000:role/eks-node-role");
        return ng;
    }

    @Test
    void createNodegroupBecomesActiveWithDefaults() {
        newCluster("ng-cluster");
        Nodegroup ng = eksService.createNodegroup("ng-cluster", newNodegroupRequest("ng1"));

        assertEquals("ng1", ng.getNodegroupName());
        assertEquals("ng-cluster", ng.getClusterName());
        assertEquals(NodegroupStatus.ACTIVE, ng.getStatus());
        assertTrue(ng.getNodegroupArn().contains("nodegroup/ng-cluster/ng1/"));
        assertEquals("AL2_x86_64", ng.getAmiType());
        assertEquals("ON_DEMAND", ng.getCapacityType());
        assertEquals(20, ng.getDiskSize());
        assertEquals("1.29", ng.getVersion());
        assertEquals(List.of("t3.medium"), ng.getInstanceTypes());
        assertNotNull(ng.getScalingConfig());
        assertEquals(2, ng.getScalingConfig().getDesiredSize());
        assertNotNull(ng.getCreatedAt());
    }

    @Test
    void createNodegroupOnMissingClusterFails() {
        AwsException e = assertThrows(AwsException.class,
                () -> eksService.createNodegroup("no-such-cluster", newNodegroupRequest("ng1")));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void createNodegroupDuplicateFails() {
        newCluster("ng-cluster");
        eksService.createNodegroup("ng-cluster", newNodegroupRequest("ng1"));
        AwsException e = assertThrows(AwsException.class,
                () -> eksService.createNodegroup("ng-cluster", newNodegroupRequest("ng1")));
        assertEquals("ResourceInUseException", e.getErrorCode());
    }

    @Test
    void describeListAndDeleteNodegroup() {
        newCluster("ng-cluster");
        eksService.createNodegroup("ng-cluster", newNodegroupRequest("ng1"));
        eksService.createNodegroup("ng-cluster", newNodegroupRequest("ng2"));

        assertEquals("ng1", eksService.describeNodegroup("ng-cluster", "ng1").getNodegroupName());
        assertEquals(List.of("ng1", "ng2"),
                eksService.listNodegroups("ng-cluster").stream().sorted().toList());

        Nodegroup deleted = eksService.deleteNodegroup("ng-cluster", "ng1");
        assertEquals(NodegroupStatus.DELETING, deleted.getStatus());
        assertThrows(AwsException.class, () -> eksService.describeNodegroup("ng-cluster", "ng1"));
        assertEquals(List.of("ng2"), eksService.listNodegroups("ng-cluster"));
    }

    @Test
    void listNodegroupsOnMissingClusterFails() {
        AwsException e = assertThrows(AwsException.class,
                () -> eksService.listNodegroups("no-such-cluster"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }
}

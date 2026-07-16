package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.CreateFargateProfileRequest;
import io.github.hectorvent.floci.services.eks.model.CreateNodeGroupRequest;
import io.github.hectorvent.floci.services.eks.model.FargateProfile;
import io.github.hectorvent.floci.services.eks.model.FargateProfileStatus;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.github.hectorvent.floci.services.eks.model.NodegroupScalingConfig;
import io.github.hectorvent.floci.services.eks.model.NodegroupStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EksServiceTest {

    private EksService eksService;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> StorageBackend<String, V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return new InMemoryStorage<>();
            }
        };

        EmulatorConfig config = testConfig();
        EksClusterManager clusterManager = null;
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        eksService = new EksService(storageFactory, config, regionResolver, clusterManager);
    }

    private EmulatorConfig testConfig() {
        EmulatorConfig.EksServiceConfig eksConfig = proxy(EmulatorConfig.EksServiceConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "enabled", "mock" -> true;
                    case "apiServerBasePort" -> 6500;
                    default -> defaultValue(method);
                });
        EmulatorConfig.ServicesConfig servicesConfig = proxy(EmulatorConfig.ServicesConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "eks" -> eksConfig;
                    default -> defaultValue(method);
                });
        return proxy(EmulatorConfig.class, (proxy, method, args) -> switch (method.getName()) {
            case "services" -> servicesConfig;
            case "defaultRegion" -> "us-east-1";
            case "defaultAccountId" -> "000000000000";
            default -> defaultValue(method);
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName() + "TestProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.invoke(this, args);
                };
            }
            return handler.invoke(proxy, method, args);
        });
    }

    private Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == Optional.class) {
            return Optional.empty();
        }
        if (returnType == String.class) {
            return "";
        }
        return null;
    }

    private void createTestCluster(String name) {
        CreateClusterRequest clusterRequest = new CreateClusterRequest();
        clusterRequest.setName(name);
        clusterRequest.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        eksService.createCluster(clusterRequest);
    }

    private CreateNodeGroupRequest nodeGroupRequest(String name) {
        CreateNodeGroupRequest request = new CreateNodeGroupRequest();
        request.setNodegroupName(name);
        request.setNodeRole("arn:aws:iam::000000000000:role/role-name");
        request.setSubnets(List.of("subnet-0e2907431c9988b72", "subnet-04ad87f71c6e5ab4d"));
        return request;
    }

    private CreateFargateProfileRequest fargateProfileRequest(String name) {
        FargateProfile.Selector selector = new FargateProfile.Selector();
        selector.setNamespace("default");
        selector.setLabels(Map.of("app", "api"));

        CreateFargateProfileRequest request = new CreateFargateProfileRequest();
        request.setFargateProfileName(name);
        request.setPodExecutionRoleArn("arn:aws:iam::000000000000:role/eks-fargate-role");
        request.setSubnets(List.of("subnet-0e2907431c9988b72", "subnet-04ad87f71c6e5ab4d"));
        request.setSelectors(List.of(selector));
        return request;
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

    @Test
    void createNodeGroupIncludesAwsShapeFields() {
        createTestCluster("my-eks-cluster");

        NodegroupScalingConfig scalingConfig = new NodegroupScalingConfig();
        scalingConfig.setMinSize(1);
        scalingConfig.setMaxSize(3);
        scalingConfig.setDesiredSize(1);

        CreateNodeGroupRequest nodeGroupRequest = new CreateNodeGroupRequest();
        nodeGroupRequest.setNodegroupName("my-eks-nodegroup");
        nodeGroupRequest.setNodeRole("arn:aws:iam::000000000000:role/role-name");
        nodeGroupRequest.setVersion("1.26");
        nodeGroupRequest.setReleaseVersion("1.26.12-20240329");
        nodeGroupRequest.setScalingConfig(scalingConfig);
        nodeGroupRequest.setSubnets(List.of("subnet-0e2907431c9988b72", "subnet-04ad87f71c6e5ab4d"));
        nodeGroupRequest.setInstanceTypes(List.of("t3.medium"));

        Nodegroup nodeGroup = eksService.createNodeGroup("my-eks-cluster", nodeGroupRequest);

        assertEquals("my-eks-nodegroup", nodeGroup.getNodegroupName());
        assertTrue(nodeGroup.getNodegroupArn().contains("nodegroup/my-eks-cluster/my-eks-nodegroup"));
        assertEquals("my-eks-cluster", nodeGroup.getClusterName());
        assertEquals(NodegroupStatus.ACTIVE, nodeGroup.getStatus());
        assertEquals("ON_DEMAND", nodeGroup.getCapacityType());
        assertEquals(3, nodeGroup.getScalingConfig().getMaxSize());
        assertEquals(List.of("t3.medium"), nodeGroup.getInstanceTypes());
        assertEquals("AL2_x86_64", nodeGroup.getAmiType());
        assertEquals("arn:aws:iam::000000000000:role/role-name", nodeGroup.getNodeRole());
        assertEquals(20, nodeGroup.getDiskSize());
        Map<?, ?> resources = (Map<?, ?>) nodeGroup.getResources();
        List<?> autoScalingGroups = (List<?>) resources.get("autoScalingGroups");
        assertEquals(1, autoScalingGroups.size());
        assertTrue(((Map<?, ?>) autoScalingGroups.getFirst()).get("name").toString()
                .startsWith("eks-my-eks-nodegroup-"));
        assertEquals(List.of(), ((Map<?, ?>) nodeGroup.getHealth()).get("issues"));
        assertEquals(1, ((Map<?, ?>) nodeGroup.getUpdateConfig()).get("maxUnavailable"));
        assertEquals("my-eks-nodegroup", eksService.listNodeGroups("my-eks-cluster").getFirst());
    }

    @Test
    void createNodeGroupDefaultsVersionFromCluster() {
        CreateClusterRequest clusterRequest = new CreateClusterRequest();
        clusterRequest.setName("my-eks-cluster");
        clusterRequest.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        clusterRequest.setVersion("1.30");
        eksService.createCluster(clusterRequest);

        Nodegroup nodeGroup = eksService.createNodeGroup("my-eks-cluster", nodeGroupRequest("my-eks-nodegroup"));

        assertEquals("1.30", nodeGroup.getVersion());
        assertEquals("1.30-eks-1", nodeGroup.getReleaseVersion());
    }

    @Test
    void nodeGroupLifecycleDescribeListDelete() {
        createTestCluster("my-eks-cluster");
        eksService.createNodeGroup("my-eks-cluster", nodeGroupRequest("nodegroup-a"));
        eksService.createNodeGroup("my-eks-cluster", nodeGroupRequest("nodegroup-b"));

        List<String> names = eksService.listNodeGroups("my-eks-cluster");
        assertEquals(2, names.size());
        assertTrue(names.contains("nodegroup-a"));
        assertTrue(names.contains("nodegroup-b"));

        Nodegroup described = eksService.describeNodeGroup("my-eks-cluster", "nodegroup-a");
        assertEquals("nodegroup-a", described.getNodegroupName());

        Nodegroup deleted = eksService.deleteNodeGroup("my-eks-cluster", "nodegroup-a");
        assertEquals(NodegroupStatus.DELETING, deleted.getStatus());
        assertThrows(AwsException.class, () -> eksService.describeNodeGroup("my-eks-cluster", "nodegroup-a"));
        assertEquals(List.of("nodegroup-b"), eksService.listNodeGroups("my-eks-cluster"));
    }

    @Test
    void createNodeGroupDuplicateFails() {
        createTestCluster("my-eks-cluster");
        CreateNodeGroupRequest request = nodeGroupRequest("my-eks-nodegroup");
        eksService.createNodeGroup("my-eks-cluster", request);

        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createNodeGroup("my-eks-cluster", request));
        assertEquals(409, ex.getHttpStatus());
    }

    @Test
    void createNodeGroupWithoutClusterFails() {
        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createNodeGroup("missing-cluster", nodeGroupRequest("my-eks-nodegroup")));
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void createNodeGroupWithoutNameFails() {
        createTestCluster("my-eks-cluster");
        CreateNodeGroupRequest request = nodeGroupRequest("");

        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createNodeGroup("my-eks-cluster", request));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void createNodeGroupWithoutNodeRoleFails() {
        createTestCluster("my-eks-cluster");
        CreateNodeGroupRequest request = nodeGroupRequest("my-eks-nodegroup");
        request.setNodeRole("");

        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createNodeGroup("my-eks-cluster", request));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void createNodeGroupWithoutSubnetsFails() {
        createTestCluster("my-eks-cluster");
        CreateNodeGroupRequest request = nodeGroupRequest("my-eks-nodegroup");
        request.setSubnets(List.of());

        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createNodeGroup("my-eks-cluster", request));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void describeAndDeleteNodeGroupNotFoundFail() {
        createTestCluster("my-eks-cluster");

        AwsException describe = assertThrows(AwsException.class,
                () -> eksService.describeNodeGroup("my-eks-cluster", "missing-nodegroup"));
        assertEquals(404, describe.getHttpStatus());

        AwsException delete = assertThrows(AwsException.class,
                () -> eksService.deleteNodeGroup("my-eks-cluster", "missing-nodegroup"));
        assertEquals(404, delete.getHttpStatus());
    }

    @Test
    void createFargateProfileIncludesAwsShapeFields() {
        createTestCluster("my-eks-cluster");

        CreateFargateProfileRequest profileRequest = fargateProfileRequest("my-fargate-profile");
        profileRequest.setTags(Map.of("env", "test"));

        FargateProfile profile = eksService.createFargateProfile("my-eks-cluster", profileRequest);

        assertEquals("my-fargate-profile", profile.getFargateProfileName());
        assertTrue(profile.getFargateProfileArn()
                .matches("arn:aws:eks:[^:]+:[0-9]+:fargateprofile/my-eks-cluster/my-fargate-profile/.+"));
        assertEquals("my-eks-cluster", profile.getClusterName());
        assertEquals(FargateProfileStatus.ACTIVE, profile.getStatus());
        assertEquals("arn:aws:iam::000000000000:role/eks-fargate-role", profile.getPodExecutionRoleArn());
        assertEquals(List.of("subnet-0e2907431c9988b72", "subnet-04ad87f71c6e5ab4d"), profile.getSubnets());
        assertEquals("default", profile.getSelectors().getFirst().getNamespace());
        assertEquals("api", profile.getSelectors().getFirst().getLabels().get("app"));
        assertTrue(profile.getHealth().getIssues().isEmpty());
        assertEquals("test", profile.getTags().get("env"));
        assertEquals("my-fargate-profile", eksService.listFargateProfiles("my-eks-cluster").getFirst());
    }

    @Test
    void fargateProfileLifecycleDescribeListDelete() {
        createTestCluster("my-eks-cluster");
        eksService.createFargateProfile("my-eks-cluster", fargateProfileRequest("profile-a"));
        eksService.createFargateProfile("my-eks-cluster", fargateProfileRequest("profile-b"));

        List<String> names = eksService.listFargateProfiles("my-eks-cluster");
        assertEquals(2, names.size());
        assertTrue(names.contains("profile-a"));
        assertTrue(names.contains("profile-b"));

        FargateProfile described = eksService.describeFargateProfile("my-eks-cluster", "profile-a");
        assertEquals("profile-a", described.getFargateProfileName());

        FargateProfile deleted = eksService.deleteFargateProfile("my-eks-cluster", "profile-a");
        assertEquals(FargateProfileStatus.DELETING, deleted.getStatus());
        assertThrows(AwsException.class, () -> eksService.describeFargateProfile("my-eks-cluster", "profile-a"));
        assertEquals(List.of("profile-b"), eksService.listFargateProfiles("my-eks-cluster"));
    }

    @Test
    void createFargateProfileDuplicateFails() {
        createTestCluster("my-eks-cluster");
        CreateFargateProfileRequest request = fargateProfileRequest("my-fargate-profile");
        eksService.createFargateProfile("my-eks-cluster", request);

        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createFargateProfile("my-eks-cluster", request));
        assertEquals(409, ex.getHttpStatus());
    }

    @Test
    void createFargateProfileWithoutClusterFails() {
        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createFargateProfile("missing-cluster", fargateProfileRequest("my-fargate-profile")));
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void createFargateProfileWithoutNameFails() {
        createTestCluster("my-eks-cluster");
        CreateFargateProfileRequest request = fargateProfileRequest("");

        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createFargateProfile("my-eks-cluster", request));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void createFargateProfileWithoutPodExecutionRoleFails() {
        createTestCluster("my-eks-cluster");
        CreateFargateProfileRequest request = fargateProfileRequest("my-fargate-profile");
        request.setPodExecutionRoleArn("");

        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createFargateProfile("my-eks-cluster", request));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void describeAndDeleteFargateProfileNotFoundFail() {
        createTestCluster("my-eks-cluster");

        AwsException describe = assertThrows(AwsException.class,
                () -> eksService.describeFargateProfile("my-eks-cluster", "missing-profile"));
        assertEquals(404, describe.getHttpStatus());

        AwsException delete = assertThrows(AwsException.class,
                () -> eksService.deleteFargateProfile("my-eks-cluster", "missing-profile"));
        assertEquals(404, delete.getHttpStatus());
    }
}

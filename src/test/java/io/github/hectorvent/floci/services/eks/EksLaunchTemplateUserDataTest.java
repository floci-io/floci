package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.UserDataPipeline;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.AmiImageResolver;
import io.github.hectorvent.floci.services.ec2.Ec2ContainerManager;
import io.github.hectorvent.floci.services.ec2.Ec2ImageCatalog;
import io.github.hectorvent.floci.services.ec2.Ec2InstanceTypeCatalog;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.CreateNodeGroupRequest;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.github.hectorvent.floci.services.eks.model.NodegroupStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EksLaunchTemplateUserDataTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String CLUSTER_NAME = "userdata-cluster";

    private EksService eksService;
    private Ec2Service ec2Service;
    private EksClusterManager clusterManager;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory(ACCOUNT);
            }
        };

        EmulatorConfig config = testConfig();
        clusterManager = mock(EksClusterManager.class);
        ec2Service = realEc2Service();
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);

        eksService = new EksService(storageFactory, config, regionResolver, clusterManager, ec2Service,
                new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class),
                mock(EksPodIdentityAssociationService.class));

        CreateClusterRequest clusterReq = new CreateClusterRequest();
        clusterReq.setName(CLUSTER_NAME);
        clusterReq.setRoleArn("arn:aws:iam::" + ACCOUNT + ":role/eks-role");
        eksService.createCluster(clusterReq);
    }

    @Test
    void nodegroupWithLaunchTemplateExecutesUserData() {
        String script = "#!/bin/bash\necho 'hello from node group' > /tmp/bootstrap.log\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        String ltId = createLaunchTemplate("lt-test-1", encoded);

        when(clusterManager.executeUserData(any(Cluster.class), eq("ng-1"), eq(encoded)))
                .thenReturn(UserDataPipeline.ExecutionResult.success(0L, "ok"));

        CreateNodeGroupRequest req = nodeGroupRequest("ng-1");
        req.setLaunchTemplate(Map.of("id", ltId, "version", "1"));

        Nodegroup ng = eksService.createNodeGroup(CLUSTER_NAME, req);

        assertEquals(NodegroupStatus.ACTIVE, ng.getStatus());
        verify(clusterManager, times(1)).executeUserData(any(Cluster.class), eq("ng-1"), eq(encoded));
        Map<?, ?> health = (Map<?, ?>) ng.getHealth();
        assertNotNull(health);
        List<?> issues = (List<?>) health.get("issues");
        assertTrue(issues.isEmpty());
    }

    @Test
    void identicalUserDataSkipsExecutionIdempotently() {
        String script = "#!/bin/bash\necho 'cluster bootstrap'\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        String ltId = createLaunchTemplate("lt-same", encoded);

        when(clusterManager.executeUserData(any(Cluster.class), eq("ng-1"), eq(encoded)))
                .thenReturn(UserDataPipeline.ExecutionResult.success(0L, "ok"));

        CreateNodeGroupRequest req1 = nodeGroupRequest("ng-1");
        req1.setLaunchTemplate(Map.of("id", ltId));
        Nodegroup ng1 = eksService.createNodeGroup(CLUSTER_NAME, req1);
        assertEquals(NodegroupStatus.ACTIVE, ng1.getStatus());

        CreateNodeGroupRequest req2 = nodeGroupRequest("ng-2");
        req2.setLaunchTemplate(Map.of("id", ltId));
        Nodegroup ng2 = eksService.createNodeGroup(CLUSTER_NAME, req2);
        assertEquals(NodegroupStatus.ACTIVE, ng2.getStatus());

        // clusterManager.executeUserData must be called only ONCE because ng-2 has identical user data
        verify(clusterManager, times(1)).executeUserData(any(Cluster.class), any(), any());
    }

    @Test
    void differingUserDataWarnsAndSkipsWithoutExecuting() {
        String script1 = "#!/bin/bash\necho 'first'\n";
        String encoded1 = Base64.getEncoder().encodeToString(script1.getBytes(StandardCharsets.UTF_8));
        String ltId1 = createLaunchTemplate("lt-first", encoded1);

        String script2 = "#!/bin/bash\necho 'second'\n";
        String encoded2 = Base64.getEncoder().encodeToString(script2.getBytes(StandardCharsets.UTF_8));
        String ltId2 = createLaunchTemplate("lt-second", encoded2);

        when(clusterManager.executeUserData(any(Cluster.class), eq("ng-1"), eq(encoded1)))
                .thenReturn(UserDataPipeline.ExecutionResult.success(0L, "ok"));

        CreateNodeGroupRequest req1 = nodeGroupRequest("ng-1");
        req1.setLaunchTemplate(Map.of("id", ltId1));
        Nodegroup ng1 = eksService.createNodeGroup(CLUSTER_NAME, req1);
        assertEquals(NodegroupStatus.ACTIVE, ng1.getStatus());

        CreateNodeGroupRequest req2 = nodeGroupRequest("ng-2");
        req2.setLaunchTemplate(Map.of("id", ltId2));
        Nodegroup ng2 = eksService.createNodeGroup(CLUSTER_NAME, req2);

        // Floci runs a single shared container per cluster; differing user data warns and skips
        assertEquals(NodegroupStatus.ACTIVE, ng2.getStatus());
        verify(clusterManager, times(1)).executeUserData(any(Cluster.class), any(), any());
        verify(clusterManager, never()).executeUserData(any(Cluster.class), eq("ng-2"), any());
    }

    @Test
    void executionFailureMarksNodegroupCreateFailed() {
        String script = "#!/bin/bash\nexit 2\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        String ltId = createLaunchTemplate("lt-fail", encoded);

        when(clusterManager.executeUserData(any(Cluster.class), eq("ng-fail"), eq(encoded)))
                .thenReturn(UserDataPipeline.ExecutionResult.failed(2L, 1, 1, "script failed"));

        CreateNodeGroupRequest req = nodeGroupRequest("ng-fail");
        req.setLaunchTemplate(Map.of("id", ltId));

        Nodegroup ng = eksService.createNodeGroup(CLUSTER_NAME, req);

        assertEquals(NodegroupStatus.CREATE_FAILED, ng.getStatus());
        Map<?, ?> health = (Map<?, ?>) ng.getHealth();
        assertNotNull(health);
        List<?> issues = (List<?>) health.get("issues");
        assertEquals(1, issues.size());

        Map<?, ?> issue = (Map<?, ?>) issues.getFirst();
        assertEquals("NodeCreationFailure", issue.get("code"));
        assertTrue(issue.get("message").toString().contains("failed with exit code 2"));
        assertEquals(List.of("ng-fail"), issue.get("resourceIds"));
    }

    @Test
    void executionTimeoutMarksNodegroupCreateFailed() {
        String script = "#!/bin/bash\nsleep 9999\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        String ltId = createLaunchTemplate("lt-timeout", encoded);

        when(clusterManager.executeUserData(any(Cluster.class), eq("ng-timeout"), eq(encoded)))
                .thenReturn(UserDataPipeline.ExecutionResult.timedOut(1, 1, 30L, "hang"));

        CreateNodeGroupRequest req = nodeGroupRequest("ng-timeout");
        req.setLaunchTemplate(Map.of("id", ltId));

        Nodegroup ng = eksService.createNodeGroup(CLUSTER_NAME, req);

        assertEquals(NodegroupStatus.CREATE_FAILED, ng.getStatus());
        Map<?, ?> health = (Map<?, ?>) ng.getHealth();
        assertNotNull(health);
        List<?> issues = (List<?>) health.get("issues");
        assertEquals(1, issues.size());

        Map<?, ?> issue = (Map<?, ?>) issues.getFirst();
        assertEquals("NodeCreationFailure", issue.get("code"));
        assertTrue(issue.get("message").toString().contains("timed out after 30 minutes"));
        assertEquals(List.of("ng-timeout"), issue.get("resourceIds"));
    }

    @Test
    void concurrentNodeGroupCreationExecutesUserDataExactlyOnce() throws InterruptedException {
        String script = "#!/bin/bash\necho 'concurrent bootstrap'\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        String ltId = createLaunchTemplate("lt-concurrent", encoded);

        CountDownLatch enteredExecution = new CountDownLatch(1);
        CountDownLatch ng2WaitingOnClaim = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);

        EksService.userDataClaimWaitingHook = ng2WaitingOnClaim::countDown;

        try {
            when(clusterManager.executeUserData(any(Cluster.class), eq("ng-1"), eq(encoded)))
                    .thenAnswer(invocation -> {
                        enteredExecution.countDown();
                        assertTrue(releaseExecution.await(5, TimeUnit.SECONDS));
                        return UserDataPipeline.ExecutionResult.success(0L, "ok");
                    });

            CreateNodeGroupRequest req1 = nodeGroupRequest("ng-1");
            req1.setLaunchTemplate(Map.of("id", ltId));
            AtomicReference<Nodegroup> ng1Ref = new AtomicReference<>();
            Thread first = new Thread(() -> ng1Ref.set(eksService.createNodeGroup(CLUSTER_NAME, req1)));
            first.start();

            // Wait until the first caller has claimed the slot and is blocked inside the container exec
            assertTrue(enteredExecution.await(5, TimeUnit.SECONDS));

            AtomicReference<Nodegroup> ng2Ref = new AtomicReference<>();
            CreateNodeGroupRequest req2 = nodeGroupRequest("ng-2");
            req2.setLaunchTemplate(Map.of("id", ltId));
            Thread second = new Thread(() -> ng2Ref.set(eksService.createNodeGroup(CLUSTER_NAME, req2)));
            second.start();

            // Wait until second caller observes the claim and begins waiting for winner's future
            assertTrue(ng2WaitingOnClaim.await(5, TimeUnit.SECONDS));

            releaseExecution.countDown();
            first.join(5000);
            second.join(5000);

            Nodegroup ng1 = ng1Ref.get();
            Nodegroup ng2 = ng2Ref.get();
            assertNotNull(ng1);
            assertNotNull(ng2);
            assertEquals(NodegroupStatus.ACTIVE, ng1.getStatus());
            assertEquals(NodegroupStatus.ACTIVE, ng2.getStatus());

            verify(clusterManager, times(1)).executeUserData(any(Cluster.class), any(), any());
            verify(clusterManager, never()).executeUserData(any(Cluster.class), eq("ng-2"), any());
        } finally {
            EksService.userDataClaimWaitingHook = null;
        }
    }

    @Test
    void concurrentNodeGroupCreationFailsBothWhenWinnerFails() throws InterruptedException {
        String script = "#!/bin/bash\nexit 1\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        String ltId = createLaunchTemplate("lt-concurrent-fail", encoded);

        CountDownLatch enteredExecution = new CountDownLatch(1);
        CountDownLatch ng2WaitingOnClaim = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);

        EksService.userDataClaimWaitingHook = ng2WaitingOnClaim::countDown;

        try {
            when(clusterManager.executeUserData(any(Cluster.class), eq("ng-1"), eq(encoded)))
                    .thenAnswer(invocation -> {
                        enteredExecution.countDown();
                        assertTrue(releaseExecution.await(5, TimeUnit.SECONDS));
                        return UserDataPipeline.ExecutionResult.failed(1L, 1, 1, "bootstrap failed");
                    });

            CreateNodeGroupRequest req1 = nodeGroupRequest("ng-1");
            req1.setLaunchTemplate(Map.of("id", ltId));
            AtomicReference<Nodegroup> ng1Ref = new AtomicReference<>();
            Thread first = new Thread(() -> ng1Ref.set(eksService.createNodeGroup(CLUSTER_NAME, req1)));
            first.start();

            // Wait until first caller has claimed the slot and is blocked inside executeUserData
            assertTrue(enteredExecution.await(5, TimeUnit.SECONDS));

            AtomicReference<Nodegroup> ng2Ref = new AtomicReference<>();
            CreateNodeGroupRequest req2 = nodeGroupRequest("ng-2");
            req2.setLaunchTemplate(Map.of("id", ltId));
            Thread second = new Thread(() -> ng2Ref.set(eksService.createNodeGroup(CLUSTER_NAME, req2)));
            second.start();

            // Wait until second caller has observed the claim and is waiting on the future
            assertTrue(ng2WaitingOnClaim.await(5, TimeUnit.SECONDS));

            // Let execution finish with failure
            releaseExecution.countDown();
            first.join(5000);
            second.join(5000);

            Nodegroup ng1 = ng1Ref.get();
            Nodegroup ng2 = ng2Ref.get();

            assertNotNull(ng1);
            assertNotNull(ng2);
            assertEquals(NodegroupStatus.CREATE_FAILED, ng1.getStatus());
            assertEquals(NodegroupStatus.CREATE_FAILED, ng2.getStatus());

            Map<?, ?> health1 = (Map<?, ?>) ng1.getHealth();
            assertNotNull(health1);
            List<?> issues1 = (List<?>) health1.get("issues");
            assertEquals(1, issues1.size());
            assertTrue(((Map<?, ?>) issues1.getFirst()).get("message").toString().contains("bootstrap failed"));

            Map<?, ?> health2 = (Map<?, ?>) ng2.getHealth();
            assertNotNull(health2);
            List<?> issues2 = (List<?>) health2.get("issues");
            assertEquals(1, issues2.size());
            assertTrue(((Map<?, ?>) issues2.getFirst()).get("message").toString().contains("bootstrap failed"));

            // Slot should be released, so subsequent retry ng-3 can run
            when(clusterManager.executeUserData(any(Cluster.class), eq("ng-3"), eq(encoded)))
                    .thenReturn(UserDataPipeline.ExecutionResult.success(0L, "ok"));

            CreateNodeGroupRequest req3 = nodeGroupRequest("ng-3");
            req3.setLaunchTemplate(Map.of("id", ltId));
            Nodegroup ng3 = eksService.createNodeGroup(CLUSTER_NAME, req3);

            assertEquals(NodegroupStatus.ACTIVE, ng3.getStatus());
            verify(clusterManager, times(1)).executeUserData(any(Cluster.class), eq("ng-1"), eq(encoded));
            verify(clusterManager, never()).executeUserData(any(Cluster.class), eq("ng-2"), eq(encoded));
            verify(clusterManager, times(1)).executeUserData(any(Cluster.class), eq("ng-3"), eq(encoded));
        } finally {
            EksService.userDataClaimWaitingHook = null;
        }
    }

    @Test
    void unstubbedClusterManagerDoesNotThrowNpe() {
        String script = "#!/bin/bash\necho 'unstubbed'\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        String ltId = createLaunchTemplate("lt-unstubbed", encoded);

        // clusterManager is a mock and executeUserData is not stubbed (returns null by default)
        CreateNodeGroupRequest req = nodeGroupRequest("ng-unstubbed");
        req.setLaunchTemplate(Map.of("id", ltId));

        Nodegroup ng = eksService.createNodeGroup(CLUSTER_NAME, req);
        assertNotNull(ng);
        assertEquals(NodegroupStatus.ACTIVE, ng.getStatus());
    }

    @Test
    void failedExecutionReleasesClaimForSubsequentRetry() {
        String script = "#!/bin/bash\nexit 3\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        String ltId = createLaunchTemplate("lt-retry", encoded);

        when(clusterManager.executeUserData(any(Cluster.class), eq("ng-1"), eq(encoded)))
                .thenReturn(UserDataPipeline.ExecutionResult.failed(3L, 1, 1, "boom"));

        CreateNodeGroupRequest req1 = nodeGroupRequest("ng-1");
        req1.setLaunchTemplate(Map.of("id", ltId));
        Nodegroup ng1 = eksService.createNodeGroup(CLUSTER_NAME, req1);
        assertEquals(NodegroupStatus.CREATE_FAILED, ng1.getStatus());

        when(clusterManager.executeUserData(any(Cluster.class), eq("ng-2"), eq(encoded)))
                .thenReturn(UserDataPipeline.ExecutionResult.success(0L, "ok"));

        CreateNodeGroupRequest req2 = nodeGroupRequest("ng-2");
        req2.setLaunchTemplate(Map.of("id", ltId));
        Nodegroup ng2 = eksService.createNodeGroup(CLUSTER_NAME, req2);

        assertEquals(NodegroupStatus.ACTIVE, ng2.getStatus());
        verify(clusterManager, times(1)).executeUserData(any(Cluster.class), eq("ng-1"), eq(encoded));
        verify(clusterManager, times(1)).executeUserData(any(Cluster.class), eq("ng-2"), eq(encoded));
    }

    @Test
    void clusterDeletionClearsAppliedUserDataTracker() {
        String script = "#!/bin/bash\necho 'recreate test'\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        String ltId = createLaunchTemplate("lt-recreate", encoded);

        when(clusterManager.executeUserData(any(Cluster.class), eq("ng-1"), eq(encoded)))
                .thenReturn(UserDataPipeline.ExecutionResult.success(0L, "ok"));

        CreateNodeGroupRequest req = nodeGroupRequest("ng-1");
        req.setLaunchTemplate(Map.of("id", ltId));
        eksService.createNodeGroup(CLUSTER_NAME, req);
        verify(clusterManager, times(1)).executeUserData(any(Cluster.class), eq("ng-1"), eq(encoded));

        // Delete nodegroup and cluster, then recreate
        eksService.deleteNodeGroup(CLUSTER_NAME, "ng-1");
        eksService.deleteCluster(CLUSTER_NAME);

        CreateClusterRequest clusterReq = new CreateClusterRequest();
        clusterReq.setName(CLUSTER_NAME);
        clusterReq.setRoleArn("arn:aws:iam::" + ACCOUNT + ":role/eks-role");
        eksService.createCluster(clusterReq);

        // Nodegroup on recreated cluster should execute user data again
        eksService.createNodeGroup(CLUSTER_NAME, req);
        verify(clusterManager, times(2)).executeUserData(any(Cluster.class), eq("ng-1"), eq(encoded));
    }

    @Test
    void clusterDeletionForAccountClearsAppliedUserDataTracker() {
        String script = "#!/bin/bash\necho 'recreate account test'\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        String ltId = createLaunchTemplate("lt-recreate-account", encoded);

        when(clusterManager.executeUserData(any(Cluster.class), eq("ng-acc"), eq(encoded)))
                .thenReturn(UserDataPipeline.ExecutionResult.success(0L, "ok"));

        CreateNodeGroupRequest req = nodeGroupRequest("ng-acc");
        req.setLaunchTemplate(Map.of("id", ltId));
        eksService.createNodeGroup(CLUSTER_NAME, req);
        verify(clusterManager, times(1)).executeUserData(any(Cluster.class), eq("ng-acc"), eq(encoded));

        // Delete nodegroup and cluster via deleteClusterForAccount, then recreate
        eksService.deleteNodeGroup(CLUSTER_NAME, "ng-acc");
        eksService.deleteClusterForAccount(ACCOUNT, CLUSTER_NAME);

        CreateClusterRequest clusterReq = new CreateClusterRequest();
        clusterReq.setName(CLUSTER_NAME);
        clusterReq.setRoleArn("arn:aws:iam::" + ACCOUNT + ":role/eks-role");
        eksService.createCluster(clusterReq);

        // Nodegroup on recreated cluster should execute user data again
        eksService.createNodeGroup(CLUSTER_NAME, req);
        verify(clusterManager, times(2)).executeUserData(any(Cluster.class), eq("ng-acc"), eq(encoded));
    }

    private String createLaunchTemplate(String name, String userData) {
        LaunchTemplateData data = new LaunchTemplateData();
        data.setUserData(userData);
        LaunchTemplate lt = ec2Service.createLaunchTemplate(REGION, name, data, List.of());
        return lt.getLaunchTemplateId();
    }

    private CreateNodeGroupRequest nodeGroupRequest(String name) {
        CreateNodeGroupRequest req = new CreateNodeGroupRequest();
        req.setNodegroupName(name);
        req.setNodeRole("arn:aws:iam::" + ACCOUNT + ":role/eks-node-role");
        req.setSubnets(List.of("subnet-12345678"));
        return req;
    }

    private Ec2Service realEc2Service() {
        EmulatorConfig ec2Config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2ServiceConfig = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(ec2Config.defaultAccountId()).thenReturn(ACCOUNT);
        when(ec2Config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2ServiceConfig);
        when(ec2ServiceConfig.mock()).thenReturn(true);

        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory(ACCOUNT);
            }
        };

        return new Ec2Service(ec2Config, mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class),
                mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), storageFactory);
    }

    private EmulatorConfig testConfig() {
        EmulatorConfig.EksServiceConfig eksConfig = proxy(EmulatorConfig.EksServiceConfig.class,
                (p, method, args) -> switch (method.getName()) {
                    case "enabled" -> true;
                    case "mock" -> true;
                    case "apiServerBasePort" -> 6500;
                    default -> defaultValue(method);
                });

        EmulatorConfig.ServicesConfig services = proxy(EmulatorConfig.ServicesConfig.class,
                (p, method, args) -> "eks".equals(method.getName()) ? eksConfig : defaultValue(method));

        return proxy(EmulatorConfig.class, (p, method, args) -> switch (method.getName()) {
            case "defaultRegion" -> REGION;
            case "defaultAccountId" -> ACCOUNT;
            case "services" -> services;
            default -> defaultValue(method);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static Object defaultValue(Method method) {
        Class<?> r = method.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        return null;
    }
}

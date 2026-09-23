package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.AmiImageResolver;
import io.github.hectorvent.floci.services.ec2.Ec2ContainerManager;
import io.github.hectorvent.floci.services.ec2.Ec2ImageCatalog;
import io.github.hectorvent.floci.services.ec2.Ec2InstanceTypeCatalog;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.services.eks.model.ClusterIdentity;
import io.github.hectorvent.floci.services.eks.model.ClusterOidcKey;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.OidcIdentity;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.CreateFargateProfileRequest;
import io.github.hectorvent.floci.services.eks.model.CreateNodeGroupRequest;
import io.github.hectorvent.floci.services.eks.model.EncryptionConfig;
import io.github.hectorvent.floci.services.eks.model.FargateProfile;
import io.github.hectorvent.floci.services.eks.model.FargateProfileStatus;
import io.github.hectorvent.floci.services.eks.model.KubernetesNetworkConfig;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.LogSetup;
import io.github.hectorvent.floci.services.eks.model.Logging;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.github.hectorvent.floci.services.eks.model.NodegroupScalingConfig;
import io.github.hectorvent.floci.services.eks.model.NodegroupStatus;
import io.github.hectorvent.floci.services.eks.model.Provider;
import io.github.hectorvent.floci.services.eks.model.ResourcesVpcConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EksServiceTest {

    private EksService eksService;
    private StorageFactory storageFactory;
    private RegionResolver regionResolver;
    private Ec2Service ec2Service;

    @BeforeEach
    void setUp() {
        storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory("000000000000");
            }
        };

        EmulatorConfig config = testConfig();
        EksClusterManager clusterManager = null;
        ec2Service = realEc2Service();
        ec2Service.createLaunchTemplate("us-east-1", "my-node-launch-template",
                new LaunchTemplateData(), null, null);
        ec2Service.createLaunchTemplateVersion("us-east-1", null, "my-node-launch-template",
                "1", new LaunchTemplateData());
        ec2Service.createLaunchTemplateVersion("us-east-1", null, "my-node-launch-template",
                "2", new LaunchTemplateData());
        regionResolver = new RegionResolver("us-east-1", "000000000000");
        eksService = new EksService(storageFactory, config, regionResolver, clusterManager, ec2Service,
                new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class),
                mock(EksPodIdentityAssociationService.class));
    }

    private EmulatorConfig testConfig() {
        return testConfig(true);
    }

    private EmulatorConfig testConfig(boolean mock) {
        return testConfig(mock, false);
    }

    private EmulatorConfig testConfig(boolean mock, boolean keepRunningOnShutdown) {
        EmulatorConfig.EksServiceConfig eksConfig = proxy(EmulatorConfig.EksServiceConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "enabled" -> true;
                    case "mock" -> mock;
                    case "keepRunningOnShutdown" -> keepRunningOnShutdown;
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

    private Ec2Service realEc2Service() {
        EmulatorConfig ec2Config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2ServiceConfig = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(ec2Config.defaultAccountId()).thenReturn("000000000000");
        when(ec2Config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2ServiceConfig);
        when(ec2ServiceConfig.mock()).thenReturn(true);

        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory("000000000000");
            }
        };

        return new Ec2Service(ec2Config, mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class),
                mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), storageFactory);
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
    void createClusterAssignsOidcIssuerAndKey() {
        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("oidc-cluster");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");

        Cluster cluster = eksService.createCluster(req);

        assertNotNull(cluster.getIdentity());
        assertNotNull(cluster.getIdentity().getOidc());
        assertTrue(cluster.getIdentity().getOidc().getIssuer()
                .matches("https://oidc\\.eks\\.us-east-1\\.amazonaws\\.com/id/[A-F0-9]{32}"));
    }

    @Test
    void initBackfillsOidcIdentityForClustersPersistedBeforeIrsaSupport() {
        // A cluster restored from storage without an identity — what an upgrade looks like — must
        // gain an issuer and key on startup, or minting and the JWKS routes stay broken for it.
        StorageBackend<String, Cluster> clusterStore = new InMemoryStorage<>();
        StorageBackend<String, ClusterOidcKey> keyStore = new InMemoryStorage<>();

        Cluster legacy = new Cluster();
        legacy.setName("legacy-cluster");
        legacy.setStatus(ClusterStatus.ACTIVE);
        clusterStore.put("legacy-cluster", legacy);
        assertNull(legacy.getIdentity());

        EksOidcService oidcService = new EksOidcService(
                fixedStorageFactory(keyStore), new ObjectMapper());
        EksService restarted = new EksService(fixedStorageFactory(clusterStore), testConfig(),
                new RegionResolver("us-east-1", "000000000000"), null, null, oidcService,
                mock(EksAccessEntryService.class), mock(EksPodIdentityAssociationService.class));
        restarted.init();

        Cluster migrated = restarted.describeCluster("legacy-cluster");
        assertNotNull(migrated.getIdentity());
        String issuer = migrated.getIdentity().getOidc().getIssuer();
        assertTrue(issuer.matches("https://oidc\\.eks\\.us-east-1\\.amazonaws\\.com/id/[A-F0-9]{32}"));
        assertTrue(oidcService.findVerificationKey(issuer).isPresent());
    }

    @Test
    void initLeavesAnExistingOidcIssuerUnchanged() {
        StorageBackend<String, Cluster> clusterStore = new InMemoryStorage<>();
        StorageBackend<String, ClusterOidcKey> keyStore = new InMemoryStorage<>();

        String issuer = "https://oidc.eks.us-east-1.amazonaws.com/id/ABCDEF0123456789ABCDEF0123456789";
        Cluster existing = new Cluster();
        existing.setName("existing-cluster");
        existing.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));
        clusterStore.put("existing-cluster", existing);

        EksOidcService oidcService = new EksOidcService(
                fixedStorageFactory(keyStore), new ObjectMapper());
        EksService restarted = new EksService(fixedStorageFactory(clusterStore), testConfig(),
                new RegionResolver("us-east-1", "000000000000"), null, null, oidcService, mock(EksAccessEntryService.class));
        restarted.init();

        // The issuer a trust policy was written against must survive a restart, and its key must
        // be present so previously minted tokens still verify.
        assertEquals(issuer,
                restarted.describeCluster("existing-cluster").getIdentity().getOidc().getIssuer());
        assertTrue(oidcService.findVerificationKey(issuer).isPresent());
    }

    @Test
    void initBackfillsUnderTheOwningAccountNotTheDefault() {
        // Startup has no request context, so the account-scoped put() resolves to the default
        // account. A cluster owned by another account must still be migrated in place, or the owner
        // keeps an issuer-less record while a duplicate appears under the default account. The
        // record itself carries no accountId (it is @JsonIgnore, dropped on reload) — the owner
        // can only come from the storage key.
        String otherAccount = "999999999999";
        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        StorageBackend<String, ClusterOidcKey> rawKeys = new InMemoryStorage<>();
        var clusterStore = new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");
        var keyStore = new AccountAwareStorageBackend<>(rawKeys, null, "000000000000");

        Cluster legacy = new Cluster();
        legacy.setName("legacy-cluster");
        legacy.setStatus(ClusterStatus.ACTIVE);
        clusterStore.putForAccount(otherAccount, "legacy-cluster", legacy);

        EksOidcService oidcService = new EksOidcService(
                fixedStorageFactory(keyStore), new ObjectMapper());
        new EksService(fixedStorageFactory(clusterStore), testConfig(),
                new RegionResolver("us-east-1", "000000000000"), null, null, oidcService, mock(EksAccessEntryService.class)).init();

        Cluster migrated = clusterStore.getForAccount(otherAccount, "legacy-cluster").orElseThrow();
        String issuer = migrated.getIdentity().getOidc().getIssuer();
        assertNotNull(issuer);
        // The owner rehydrated from the storage key sticks to the record for later puts.
        assertEquals(otherAccount, migrated.getAccountId());
        // No duplicate stranded under the default account.
        assertTrue(clusterStore.getForAccount("000000000000", "legacy-cluster").isEmpty());
        // The signing key is stored under the owner too, and is still resolvable by issuer.
        assertTrue(keyStore.getForAccount(otherAccount, "legacy-cluster").isPresent());
        assertTrue(oidcService.findVerificationKey(issuer).isPresent());
    }

    @Test
    void initRestoresPersistedClustersAfterARestart() {
        // A cluster restored from eks-clusters.json after a Floci/Docker restart (#2609) has no
        // container attached — init must re-latch it and hand it back to the readiness poller.
        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        var clusterStore = new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");
        Cluster persisted = new Cluster();
        persisted.setName("persisted-cluster");
        persisted.setStatus(ClusterStatus.ACTIVE);
        clusterStore.putForAccount("000000000000", "persisted-cluster", persisted);

        Cluster failed = new Cluster();
        failed.setName("failed-cluster");
        failed.setStatus(ClusterStatus.FAILED);
        clusterStore.putForAccount("000000000000", "failed-cluster", failed);

        EksClusterManager clusterManager = mock(EksClusterManager.class);
        EksService restarted = new EksService(fixedStorageFactory(clusterStore), testConfig(false),
                new RegionResolver("us-east-1", "000000000000"), clusterManager, null,
                new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()),
                        new ObjectMapper()), mock(EksAccessEntryService.class));
        try {
            restarted.init();

            verify(clusterManager).restoreCluster(persisted);
            // CREATING hands the cluster to the readiness poller, which re-extracts the CA and
            // flips it back to ACTIVE once the API server answers.
            assertEquals(ClusterStatus.CREATING,
                    restarted.describeCluster("persisted-cluster").getStatus());
            // A FAILED record has nothing to re-latch.
            verify(clusterManager, never()).restoreCluster(failed);
            assertEquals(ClusterStatus.FAILED,
                    restarted.describeCluster("failed-cluster").getStatus());
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void initRestoresUnderTheOwningAccountNotTheDefault() {
        // Cluster.accountId is @JsonIgnore: a record reloaded from eks-clusters.json carries no
        // account — only its storage key does. Restoration must derive the owner from the key,
        // or the restored state lands under the default account while the owner keeps a stale
        // record with no restored runtime fields.
        String otherAccount = "999999999999";
        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        var clusterStore = new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");

        Cluster persisted = new Cluster();
        persisted.setName("persisted-cluster");
        persisted.setStatus(ClusterStatus.ACTIVE);
        // An existing identity keeps backfillOidcIdentities from writing the record itself.
        persisted.setIdentity(new ClusterIdentity(new OidcIdentity(
                "https://oidc.eks.us-east-1.amazonaws.com/id/ABCDEF0123456789ABCDEF0123456789")));
        clusterStore.putForAccount(otherAccount, "persisted-cluster", persisted);

        EksClusterManager clusterManager = mock(EksClusterManager.class);
        EksService restarted = new EksService(fixedStorageFactory(clusterStore), testConfig(false),
                new RegionResolver("us-east-1", "000000000000"), clusterManager, null,
                new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()),
                        new ObjectMapper()), mock(EksAccessEntryService.class));
        try {
            restarted.init();

            verify(clusterManager).restoreCluster(persisted);
            Cluster restored = clusterStore.getForAccount(otherAccount, "persisted-cluster").orElseThrow();
            assertEquals(ClusterStatus.CREATING, restored.getStatus());
            // Rehydrated from the storage key, so the readiness poller's later put also lands
            // under the owner.
            assertEquals(otherAccount, restored.getAccountId());
            // No duplicate stranded under the default account.
            assertTrue(clusterStore.getForAccount("000000000000", "persisted-cluster").isEmpty());
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void initDoesNotRestoreClustersWithNamesOutsideTheAwsCharset() {
        // A record persisted before create-time name validation can carry a name with a dot —
        // which would map to another account's qualified Docker name (999999999999.demo in the
        // default account aliases account 999999999999's "demo"). Restoration must refuse it
        // rather than adopt or remove that account's container.
        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        var clusterStore = new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");
        Cluster invalid = new Cluster();
        invalid.setName("999999999999.demo");
        invalid.setStatus(ClusterStatus.ACTIVE);
        clusterStore.putForAccount("000000000000", "999999999999.demo", invalid);

        EksClusterManager clusterManager = mock(EksClusterManager.class);
        EksService restarted = new EksService(fixedStorageFactory(clusterStore), testConfig(false),
                new RegionResolver("us-east-1", "000000000000"), clusterManager, null,
                new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()),
                        new ObjectMapper()), mock(EksAccessEntryService.class));
        try {
            restarted.init();

            verify(clusterManager, never()).restoreCluster(any(Cluster.class));
            assertEquals(ClusterStatus.FAILED,
                    restarted.describeCluster("999999999999.demo").getStatus());
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void initMarksAClusterFailedWhenItsContainerCannotBeRestored() {
        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        var clusterStore = new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");
        Cluster persisted = new Cluster();
        persisted.setName("persisted-cluster");
        persisted.setStatus(ClusterStatus.ACTIVE);
        clusterStore.putForAccount("000000000000", "persisted-cluster", persisted);

        EksClusterManager clusterManager = mock(EksClusterManager.class);
        // The daemon is reachable, so this is a genuine restore failure: the daemonless
        // degradation path (which marks the cluster metadata-only ACTIVE) must not kick in.
        when(clusterManager.isDockerReachable()).thenReturn(true);
        doThrow(new RuntimeException("bad container state")).when(clusterManager).restoreCluster(persisted);
        EksService restarted = new EksService(fixedStorageFactory(clusterStore), testConfig(false),
                new RegionResolver("us-east-1", "000000000000"), clusterManager, null,
                new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()),
                        new ObjectMapper()), mock(EksAccessEntryService.class));
        try {
            restarted.init();

            // Better an honest FAILED than an ACTIVE cluster no kubectl can reach.
            assertEquals(ClusterStatus.FAILED,
                    restarted.describeCluster("persisted-cluster").getStatus());
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void initRestoresAClusterAsMetadataOnlyWhenNoDockerDaemonIsReachable() {
        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        var clusterStore = new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");
        Cluster persisted = new Cluster();
        persisted.setName("persisted-cluster");
        persisted.setStatus(ClusterStatus.ACTIVE);
        clusterStore.putForAccount("000000000000", "persisted-cluster", persisted);

        EksClusterManager clusterManager = mock(EksClusterManager.class);
        when(clusterManager.isDockerReachable()).thenReturn(false);
        doThrow(new RuntimeException("no docker")).when(clusterManager).restoreCluster(persisted);
        EksService restarted = new EksService(fixedStorageFactory(clusterStore), testConfig(false),
                new RegionResolver("us-east-1", "000000000000"), clusterManager, null,
                new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()),
                        new ObjectMapper()), mock(EksAccessEntryService.class));
        try {
            restarted.init();

            // Same degradation as create: losing the daemon is not the cluster's fault.
            assertEquals(ClusterStatus.ACTIVE,
                    restarted.describeCluster("persisted-cluster").getStatus());
        } finally {
            restarted.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void shutdownLeavesClusterContainersRunningWhenRetentionIsEnabled() {
        Cluster cluster = activeCluster();
        EksClusterManager clusterManager = mock(EksClusterManager.class);
        EksService service = serviceWithCluster(cluster, clusterManager, true);

        service.shutdown();

        verify(clusterManager).detachCluster(cluster);
        verify(clusterManager, never()).stopCluster(any(Cluster.class));
    }

    @Test
    void shutdownStopsClusterContainersWhenRetentionIsDisabled() {
        Cluster cluster = activeCluster();
        EksClusterManager clusterManager = mock(EksClusterManager.class);
        EksService service = serviceWithCluster(cluster, clusterManager, false);

        service.shutdown();

        verify(clusterManager).stopCluster(cluster);
        verify(clusterManager, never()).detachCluster(any(Cluster.class));
    }

    private static Cluster activeCluster() {
        Cluster cluster = new Cluster();
        cluster.setName("running-cluster");
        cluster.setStatus(ClusterStatus.ACTIVE);
        return cluster;
    }

    private EksService serviceWithCluster(Cluster cluster, EksClusterManager clusterManager,
            boolean keepRunningOnShutdown) {
        AccountAwareStorageBackend<Cluster> clusterStore =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, "000000000000");
        clusterStore.putForAccount("000000000000", cluster.getName(), cluster);
        return new EksService(fixedStorageFactory(clusterStore), testConfig(false, keepRunningOnShutdown),
                new RegionResolver("us-east-1", "000000000000"), clusterManager, null,
                new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()),
                        new ObjectMapper()), mock(EksAccessEntryService.class));
    }

    private StorageFactory fixedStorageFactory(StorageBackend<String, ?> backend) {
        return new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                if (backend instanceof AccountAwareStorageBackend<?> aware) {
                    return (AccountAwareStorageBackend<V>) aware;
                }
                return new AccountAwareStorageBackend<>(
                        (StorageBackend<String, V>) backend, null, "000000000000");
            }
        };
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
    void createClusterRejectsNamesOutsideTheAwsCharset() {
        // Matches real EKS validation. The dot matters most: EksClusterManager account-qualifies
        // Docker names as <account>.<name>, so a name containing a dot could spell out another
        // account's qualified name and collide with its container and data volume.
        for (String invalid : List.of("999999999999.demo", "has space", "-starts-with-dash",
                "_starts-with-underscore", "a".repeat(101))) {
            CreateClusterRequest req = new CreateClusterRequest();
            req.setName(invalid);
            req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");

            AwsException ex = assertThrows(AwsException.class, () -> eksService.createCluster(req),
                    "should reject: " + invalid);
            assertEquals("InvalidParameterException", ex.getErrorCode());
            assertEquals(400, ex.getHttpStatus());
        }
        assertTrue(eksService.listClusters().isEmpty());
    }

    @Test
    void createClusterAcceptsTheFullAwsNameCharset() {
        createTestCluster("Valid-Name_123");

        assertTrue(eksService.listClusters().contains("Valid-Name_123"));
    }

    @Test
    void createClusterWithNonExistentSubnetFails() {
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory("000000000000");
            }
        };
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null, realEc2Service(),
                new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class));

        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(List.of("subnet-1", "subnet-2"));

        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("fake-subnet-cluster");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setResourcesVpcConfig(vpcConfig);

        AwsException ex = assertThrows(AwsException.class, () -> service.createCluster(req));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(service.listClusters().isEmpty());
    }

    @Test
    void createClusterWithExistingSubnetSucceeds() {
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory("000000000000");
            }
        };
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null, realEc2Service(),
                new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class));

        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(List.of(Ec2Service.defaultSubnetId("us-east-1", "a"),
                Ec2Service.defaultSubnetId("us-east-1", "b")));

        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("real-subnet-cluster");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setResourcesVpcConfig(vpcConfig);

        Cluster cluster = service.createCluster(req);

        assertEquals("real-subnet-cluster", cluster.getName());
        assertEquals(List.of(Ec2Service.defaultSubnetId("us-east-1", "a"),
                Ec2Service.defaultSubnetId("us-east-1", "b")),
                cluster.getResourcesVpcConfig().getSubnetIds());
    }

    @Test
    void createClusterResolvesVpcIdFromTheSubnets() {
        // The second half of #1942: resourcesVpcConfig.vpcId came back blank.
        //
        // CreateCluster does not carry a vpcId — real EKS derives it from the
        // subnets, and so should this. requireSubnet already returns the
        // resolved Subnet, which carries the vpcId; it was simply discarded.
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory("000000000000");
            }
        };
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null,
                realEc2Service(), new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class));

        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(List.of(Ec2Service.defaultSubnetId("us-east-1", "a"),
                Ec2Service.defaultSubnetId("us-east-1", "b")));

        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("vpc-id-cluster");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setResourcesVpcConfig(vpcConfig);

        Cluster cluster = service.createCluster(req);

        assertEquals(Ec2Service.defaultVpcId("us-east-1"), cluster.getResourcesVpcConfig().getVpcId());
    }

    @Test
    void createClusterBuildsArnFromRequestRegionNotDefaultRegion() {
        // testConfig() always reports defaultRegion = "us-east-1"; the request's
        // region is eu-west-2. This pins the ARN only — see the test below for
        // the validation half.
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory("000000000000");
            }
        };
        RegionResolver regionResolver = new RegionResolver("eu-west-2", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null,
                realEc2Service(), new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class));

        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(List.of(Ec2Service.defaultSubnetId("eu-west-2", "a")));

        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("cross-region-cluster");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setResourcesVpcConfig(vpcConfig);

        Cluster cluster = service.createCluster(req);

        assertEquals("cross-region-cluster", cluster.getName());
        assertTrue(cluster.getArn().contains("eu-west-2"));
    }

    @Test
    void createClusterValidatesSubnetsInRequestRegionNotDefaultRegion() {
        // A subnet that exists in the DEFAULT region and nowhere else.
        //
        // The distinction matters: requireSubnet() calls ensureDefaultResources()
        // on whatever region it is handed, which seeds that region's own default
        // subnets there on the spot. Before #21's fix, those default subnets shared
        // the same literal id in every region, so a default subnet id resolved in
        // EVERY region and could not discriminate between "validated against the
        // request region" and "validated against the configured default" — a test
        // written with one passes with or without the fix.
        //
        // An explicitly created subnet is not seeded anywhere else, so asking
        // for it from a different region is the only thing that pins the
        // behaviour.
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory("000000000000");
            }
        };
        Ec2Service ec2Service = realEc2Service();
        ec2Service.ensureDefaultResources("us-east-1");
        String usEastOnlySubnet = ec2Service
                .createSubnet("us-east-1", Ec2Service.defaultVpcId("us-east-1"), "172.31.200.0/24", "us-east-1a")
                .getSubnetId();

        // The request is for eu-west-2, where that subnet does not exist.
        RegionResolver regionResolver = new RegionResolver("eu-west-2", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null,
                ec2Service, new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class));

        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(List.of(usEastOnlySubnet));

        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("wrong-region-subnet-cluster");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setResourcesVpcConfig(vpcConfig);

        // Resolving against config.defaultRegion() would find it in us-east-1
        // and let the cluster through, which is the bug.
        AwsException ex = assertThrows(AwsException.class, () -> service.createCluster(req));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(service.listClusters().isEmpty());
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
    void deleteClusterDeletesAddons() {
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory("000000000000");
            }
        };

        EksAddonService mockAddons = mock(EksAddonService.class);
        EksService service = new EksService(storageFactory, testConfig(),
                new RegionResolver("us-east-1", "000000000000"), null, null,
                new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class),
                mock(EksPodIdentityAssociationService.class), mockAddons);

        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("addons-cluster-to-delete");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        service.createCluster(req);

        service.deleteCluster("addons-cluster-to-delete");
        verify(mockAddons).deleteClusterAddons(any(Cluster.class));
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

    private EksService newService(EksClusterManager clusterManager, boolean mock) {
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory("000000000000");
            }
        };
        return new EksService(storageFactory, testConfig(mock),
                new RegionResolver("us-east-1", "000000000000"), clusterManager, null,
                new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class));
    }

    @Test
    void createClusterStaysCreatingWhileTheK3sContainerBoots() {
        EksClusterManager clusterManager = mock(EksClusterManager.class);
        when(clusterManager.tryStartCluster(any())).thenReturn(true);
        EksService service = newService(clusterManager, false);

        CreateClusterRequest request = new CreateClusterRequest();
        request.setName("real-eks");
        request.setRoleArn("arn:aws:iam::000000000000:role/eks-role");

        assertEquals(ClusterStatus.CREATING, service.createCluster(request).getStatus());
    }

    @Test
    void createClusterReachesActiveMetadataWhenNoDockerDaemonIsReachable() {
        EksClusterManager clusterManager = mock(EksClusterManager.class);
        when(clusterManager.tryStartCluster(any())).thenReturn(false);
        EksService service = newService(clusterManager, false);

        CreateClusterRequest request = new CreateClusterRequest();
        request.setName("probe-eks");
        request.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        request.setTags(Map.of("team", "probe1"));

        Cluster cluster = service.createCluster(request);

        assertEquals(ClusterStatus.ACTIVE, cluster.getStatus());
        assertEquals("https://localhost:6500", cluster.getEndpoint());
        assertEquals("probe1", cluster.getTags().get("team"));
        // No k3s API server exists. The empty CA is what says so.
        assertEquals("", cluster.getCertificateAuthority().getData());
        assertNull(cluster.getContainerId());
    }

    @Test
    void clusterMetadataCrudWorksWhenNoDockerDaemonIsReachable() {
        EksClusterManager clusterManager = mock(EksClusterManager.class);
        when(clusterManager.tryStartCluster(any())).thenReturn(false);
        EksService service = newService(clusterManager, false);

        CreateClusterRequest request = new CreateClusterRequest();
        request.setName("probe-eks");
        request.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        request.setTags(Map.of("team", "probe1"));
        String arn = service.createCluster(request).getArn();

        assertEquals(ClusterStatus.ACTIVE, service.describeCluster("probe-eks").getStatus());
        assertEquals(List.of("probe-eks"), service.listClusters());
        assertEquals("probe1", service.listTagsForResource(arn).get("team"));

        service.tagResource(arn, Map.of("Name", "probe-eks"));
        assertEquals("probe-eks", service.listTagsForResource(arn).get("Name"));

        assertEquals(NodegroupStatus.ACTIVE,
                service.createNodeGroup("probe-eks", nodeGroupRequest("ng-1")).getStatus());

        service.deleteCluster("probe-eks");
        assertThrows(AwsException.class, () -> service.describeCluster("probe-eks"));
    }

    @Test
    void createClusterStillFailsOnAGenuineProvisioningErrorWithAReachableDaemon() {
        EksClusterManager clusterManager = mock(EksClusterManager.class);
        when(clusterManager.tryStartCluster(any()))
                .thenThrow(new RuntimeException("no such image: rancher/k3s"));
        EksService service = newService(clusterManager, false);

        CreateClusterRequest request = new CreateClusterRequest();
        request.setName("broken-eks");
        request.setRoleArn("arn:aws:iam::000000000000:role/eks-role");

        assertEquals(ClusterStatus.FAILED, service.createCluster(request).getStatus());
    }

    @Test
    void createClusterCreatesClusterSecurityGroupInResolvedVpc() {
        Ec2Service ec2Service = realEc2Service();
        ec2Service.ensureDefaultResources("us-east-1");
        String defaultVpc = Ec2Service.defaultVpcId("us-east-1");
        String subnetId = ec2Service
                .createSubnet("us-east-1", defaultVpc, "172.31.100.0/24", "us-east-1a")
                .getSubnetId();

        StorageFactory storageFactory = fixedStorageFactory(new InMemoryStorage<>());
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null,
                ec2Service, new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class));

        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(List.of(subnetId));

        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("cluster-sg-test");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setResourcesVpcConfig(vpcConfig);

        Cluster created = service.createCluster(req);
        assertNotNull(created.getResourcesVpcConfig());
        String sgId = created.getResourcesVpcConfig().getClusterSecurityGroupId();
        assertNotNull(sgId);
        assertTrue(sgId.startsWith("sg-"), "Security group ID should start with sg-");

        Cluster described = service.describeCluster("cluster-sg-test");
        assertEquals(sgId, described.getResourcesVpcConfig().getClusterSecurityGroupId());

        List<SecurityGroup> sgs = ec2Service.describeSecurityGroups("us-east-1", List.of(sgId), List.of(), Map.of());
        assertEquals(1, sgs.size());
        SecurityGroup sg = sgs.getFirst();
        assertEquals(sgId, sg.getGroupId());
        assertTrue(sg.getGroupName().matches("^eks-cluster-sg-cluster-sg-test-[0-9a-f]{8}$"),
                "Group name should match pattern, got: " + sg.getGroupName());
        assertEquals("EKS created security group applied to ENI that is attached to EKS Control Plane master nodes, as well as any managed workloads.",
                sg.getDescription());
        assertEquals(defaultVpc, sg.getVpcId());

        Map<String, String> tagMap = sg.getTags().stream()
                .collect(Collectors.toMap(Tag::getKey, Tag::getValue));
        assertEquals(sg.getGroupName(), tagMap.get("Name"));
        assertEquals("owned", tagMap.get("kubernetes.io/cluster/cluster-sg-test"));
        assertEquals("cluster-sg-test", tagMap.get("aws:eks:cluster-name"));

        assertEquals(1, sg.getIpPermissions().size());
        assertEquals("-1", sg.getIpPermissions().getFirst().getIpProtocol());
        assertEquals(sgId, sg.getIpPermissions().getFirst().getUserIdGroupPairs().getFirst().getGroupId());

        boolean hasSelfEgress = sg.getIpPermissionsEgress().stream()
                .anyMatch(p -> "-1".equals(p.getIpProtocol())
                        && p.getUserIdGroupPairs().stream().anyMatch(u -> sgId.equals(u.getGroupId())));
        assertTrue(hasSelfEgress, "Security group should have self-referencing outbound egress rule for EFA");
    }

    @Test
    void createClusterWithoutVpcLeavesClusterSecurityGroupIdNull() {
        StorageFactory storageFactory = fixedStorageFactory(new InMemoryStorage<>());
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        Ec2Service ec2Service = realEc2Service();
        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null,
                ec2Service, new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class));

        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("cluster-no-vpc");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");

        Cluster created = service.createCluster(req);
        assertNotNull(created.getResourcesVpcConfig());
        assertEquals("", created.getResourcesVpcConfig().getVpcId());
        assertNull(created.getResourcesVpcConfig().getClusterSecurityGroupId());
    }

    @Test
    void deleteClusterDeletesClusterSecurityGroup() {
        Ec2Service ec2Service = realEc2Service();
        ec2Service.ensureDefaultResources("us-east-1");
        String defaultVpc = Ec2Service.defaultVpcId("us-east-1");
        String subnetId = ec2Service
                .createSubnet("us-east-1", defaultVpc, "172.31.101.0/24", "us-east-1a")
                .getSubnetId();

        StorageFactory storageFactory = fixedStorageFactory(new InMemoryStorage<>());
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null,
                ec2Service, new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class));

        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(List.of(subnetId));

        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("cluster-to-delete");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setResourcesVpcConfig(vpcConfig);

        Cluster created = service.createCluster(req);
        String sgId = created.getResourcesVpcConfig().getClusterSecurityGroupId();
        assertFalse(ec2Service.describeSecurityGroups("us-east-1", List.of(sgId), List.of(), Map.of()).isEmpty());

        service.deleteCluster("cluster-to-delete");
        assertTrue(ec2Service.describeSecurityGroups("us-east-1", List.of(sgId), List.of(), Map.of()).isEmpty());
    }

    @Test
    void deleteClusterSucceedsWhenClusterSecurityGroupAlreadyDeleted() {
        Ec2Service ec2Service = realEc2Service();
        ec2Service.ensureDefaultResources("us-east-1");
        String defaultVpc = Ec2Service.defaultVpcId("us-east-1");
        String subnetId = ec2Service
                .createSubnet("us-east-1", defaultVpc, "172.31.102.0/24", "us-east-1a")
                .getSubnetId();

        StorageFactory storageFactory = fixedStorageFactory(new InMemoryStorage<>());
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null,
                ec2Service, new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class));

        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(List.of(subnetId));

        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("cluster-pre-deleted-sg");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setResourcesVpcConfig(vpcConfig);

        Cluster created = service.createCluster(req);
        String sgId = created.getResourcesVpcConfig().getClusterSecurityGroupId();

        // Delete the security group before deleting the cluster
        ec2Service.deleteSecurityGroup("us-east-1", sgId);

        // Deleting the cluster must tolerate InvalidGroup.NotFound
        Cluster deleted = service.deleteCluster("cluster-pre-deleted-sg");
        assertEquals(ClusterStatus.DELETING, deleted.getStatus());
    }

    @Test
    void deleteClusterPropagatesGenuineEc2Failure() {
        Ec2Service mockEc2 = mock(Ec2Service.class);
        doThrow(new AwsException("DependencyViolation", "Rules reference this group", 400))
                .when(mockEc2).deleteSecurityGroup(any(), any());

        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        AccountAwareStorageBackend<Cluster> clusterStore =
                new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");

        Cluster cluster = new Cluster();
        cluster.setName("failing-delete-cluster");
        cluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/failing-delete-cluster");
        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setClusterSecurityGroupId("sg-12345678");
        cluster.setResourcesVpcConfig(vpcConfig);
        clusterStore.putForAccount("000000000000", "failing-delete-cluster", cluster);

        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        StorageFactory storageFactory = fixedStorageFactory(clusterStore);
        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null,
                mockEc2, new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()), new ObjectMapper()), mock(EksAccessEntryService.class));

        AwsException ex = assertThrows(AwsException.class, () -> service.deleteCluster("failing-delete-cluster"));
        assertEquals("DependencyViolation", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void backfillClusterSecurityGroupOnStartup() {
        Ec2Service ec2Service = realEc2Service();
        ec2Service.ensureDefaultResources("us-east-1");
        String defaultVpc = Ec2Service.defaultVpcId("us-east-1");

        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        AccountAwareStorageBackend<Cluster> clusterStore =
                new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");

        Cluster legacyCluster = new Cluster();
        legacyCluster.setName("legacy-eks");
        legacyCluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/legacy-eks");
        legacyCluster.setStatus(ClusterStatus.ACTIVE);
        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setVpcId(defaultVpc);
        vpcConfig.setClusterSecurityGroupId(null);
        legacyCluster.setResourcesVpcConfig(vpcConfig);
        clusterStore.putForAccount("000000000000", "legacy-eks", legacyCluster);

        StorageFactory storageFactory = fixedStorageFactory(clusterStore);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(true), regionResolver, null,
                ec2Service, new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()), new ObjectMapper()), mock(EksAccessEntryService.class));

        service.init();

        Cluster described = service.describeCluster("legacy-eks");
        String sgId = described.getResourcesVpcConfig().getClusterSecurityGroupId();
        assertNotNull(sgId);
        assertTrue(sgId.startsWith("sg-"));

        List<SecurityGroup> sgs = ec2Service.describeSecurityGroups("us-east-1", List.of(sgId), List.of(), Map.of());
        assertEquals(1, sgs.size());
        SecurityGroup sg = sgs.getFirst();
        assertEquals(defaultVpc, sg.getVpcId());
        assertTrue(sg.getGroupName().startsWith("eks-cluster-sg-legacy-eks-"));
    }

    @Test
    void backfillClusterSecurityGroupLeavesExistingSecurityGroupUnchanged() {
        Ec2Service ec2Service = realEc2Service();
        ec2Service.ensureDefaultResources("us-east-1");
        String defaultVpc = Ec2Service.defaultVpcId("us-east-1");

        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        AccountAwareStorageBackend<Cluster> clusterStore =
                new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");

        Cluster existingCluster = new Cluster();
        existingCluster.setName("existing-eks");
        existingCluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/existing-eks");
        existingCluster.setStatus(ClusterStatus.ACTIVE);
        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setVpcId(defaultVpc);
        vpcConfig.setClusterSecurityGroupId("sg-already-present");
        existingCluster.setResourcesVpcConfig(vpcConfig);
        clusterStore.putForAccount("000000000000", "existing-eks", existingCluster);

        StorageFactory storageFactory = fixedStorageFactory(clusterStore);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(true), regionResolver, null,
                ec2Service, new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()), new ObjectMapper()), mock(EksAccessEntryService.class));

        service.init();

        Cluster described = service.describeCluster("existing-eks");
        assertEquals("sg-already-present", described.getResourcesVpcConfig().getClusterSecurityGroupId());
    }

    @Test
    void restartRoundTripPersistsClusterSecurityGroupId() {
        Ec2Service ec2Service = realEc2Service();
        ec2Service.ensureDefaultResources("us-east-1");
        String defaultVpc = Ec2Service.defaultVpcId("us-east-1");
        String subnetId = ec2Service
                .createSubnet("us-east-1", defaultVpc, "172.31.103.0/24", "us-east-1a")
                .getSubnetId();

        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        AccountAwareStorageBackend<Cluster> clusterStore =
                new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");
        StorageFactory storageFactory = fixedStorageFactory(clusterStore);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        StorageFactory oidcStorageFactory = fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>());

        EksService firstRun = new EksService(storageFactory, testConfig(), regionResolver, null,
                ec2Service, new EksOidcService(oidcStorageFactory, new ObjectMapper()), mock(EksAccessEntryService.class));

        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(List.of(subnetId));

        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("round-trip-cluster");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setResourcesVpcConfig(vpcConfig);

        Cluster created = firstRun.createCluster(req);
        String sgId = created.getResourcesVpcConfig().getClusterSecurityGroupId();
        assertNotNull(sgId);
        assertTrue(sgId.startsWith("sg-"));

        // Simulate restart by creating a new EksService instance backed by the same storage and ec2Service
        EksService restarted = new EksService(storageFactory, testConfig(true), regionResolver, null,
                ec2Service, new EksOidcService(oidcStorageFactory, new ObjectMapper()), mock(EksAccessEntryService.class));
        restarted.init();

        Cluster described = restarted.describeCluster("round-trip-cluster");
        assertEquals(sgId, described.getResourcesVpcConfig().getClusterSecurityGroupId());

        // And verify it still resolves in EC2
        List<SecurityGroup> sgs = ec2Service.describeSecurityGroups("us-east-1", List.of(sgId), List.of(), Map.of());
        assertEquals(1, sgs.size());
        assertEquals(sgId, sgs.getFirst().getGroupId());
    }

    @Test
    void createClusterWithNonExistentVpcThrowsInvalidParameterException() {
        StorageFactory storageFactory = fixedStorageFactory(new InMemoryStorage<>());
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        Ec2Service ec2Service = realEc2Service();
        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null,
                ec2Service, new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class));

        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setVpcId("vpc-nonexistent");

        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("cluster-invalid-vpc");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setResourcesVpcConfig(vpcConfig);

        AwsException ex = assertThrows(AwsException.class, () -> service.createCluster(req));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("vpc-nonexistent"));
    }

    @Test
    void createClusterCleansUpSecurityGroupWhenClusterCreationFails() {
        Ec2Service ec2Service = realEc2Service();
        ec2Service.ensureDefaultResources("us-east-1");
        String defaultVpc = Ec2Service.defaultVpcId("us-east-1");
        String subnetId = ec2Service
                .createSubnet("us-east-1", defaultVpc, "172.31.104.0/24", "us-east-1a")
                .getSubnetId();

        StorageBackend<String, Cluster> failingStorage = new InMemoryStorage<>() {
            @Override
            public void put(String key, Cluster value) {
                throw new RuntimeException("Simulated storage failure");
            }
        };
        StorageFactory storageFactory = fixedStorageFactory(failingStorage);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null,
                ec2Service, new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()), new ObjectMapper()), mock(EksAccessEntryService.class));

        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(List.of(subnetId));

        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("cluster-leak-test");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setResourcesVpcConfig(vpcConfig);

        assertThrows(RuntimeException.class, () -> service.createCluster(req));

        List<SecurityGroup> allSgs = ec2Service.describeSecurityGroups("us-east-1", List.of(), List.of(), Map.of());
        boolean leaked = allSgs.stream().anyMatch(sg -> sg.getGroupName().startsWith("eks-cluster-sg-cluster-leak-test-"));
        assertFalse(leaked, "Cluster security group should have been deleted when cluster creation failed");
    }

    @Test
    void backfillClusterSecurityGroupsSkipsMissingVpcWithoutFailingStartup() {
        Ec2Service ec2Service = realEc2Service();
        ec2Service.ensureDefaultResources("us-east-1");
        String defaultVpc = Ec2Service.defaultVpcId("us-east-1");

        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        AccountAwareStorageBackend<Cluster> clusterStore =
                new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");

        Cluster brokenCluster = new Cluster();
        brokenCluster.setName("broken-eks");
        brokenCluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/broken-eks");
        brokenCluster.setStatus(ClusterStatus.ACTIVE);
        ResourcesVpcConfig brokenVpc = new ResourcesVpcConfig();
        brokenVpc.setVpcId("vpc-missing");
        brokenCluster.setResourcesVpcConfig(brokenVpc);
        clusterStore.putForAccount("000000000000", "broken-eks", brokenCluster);

        Cluster validCluster = new Cluster();
        validCluster.setName("valid-eks");
        validCluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/valid-eks");
        validCluster.setStatus(ClusterStatus.ACTIVE);
        ResourcesVpcConfig validVpc = new ResourcesVpcConfig();
        validVpc.setVpcId(defaultVpc);
        validCluster.setResourcesVpcConfig(validVpc);
        clusterStore.putForAccount("000000000000", "valid-eks", validCluster);

        StorageFactory storageFactory = fixedStorageFactory(clusterStore);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(true), regionResolver, null,
                ec2Service, new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()), new ObjectMapper()), mock(EksAccessEntryService.class));

        assertDoesNotThrow(service::init);

        Cluster describedBroken = service.describeCluster("broken-eks");
        assertNull(describedBroken.getResourcesVpcConfig().getClusterSecurityGroupId());

        Cluster describedValid = service.describeCluster("valid-eks");
        String validSgId = describedValid.getResourcesVpcConfig().getClusterSecurityGroupId();
        assertNotNull(validSgId);
        assertTrue(validSgId.startsWith("sg-"));
    }

    @Test
    void backfillClusterSecurityGroupsInNonDefaultAccount() {
        String nonDefaultAccount = "123456789012";
        Ec2Service ec2Service = realEc2Service();
        ec2Service.ensureDefaultResources("us-east-1");
        String defaultVpc = Ec2Service.defaultVpcId("us-east-1");

        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        AccountAwareStorageBackend<Cluster> clusterStore =
                new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");

        Cluster legacyCluster = new Cluster();
        legacyCluster.setName("non-default-eks");
        legacyCluster.setArn("arn:aws:eks:us-east-1:" + nonDefaultAccount + ":cluster/non-default-eks");
        legacyCluster.setAccountId(nonDefaultAccount);
        legacyCluster.setStatus(ClusterStatus.ACTIVE);
        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setVpcId(defaultVpc);
        legacyCluster.setResourcesVpcConfig(vpcConfig);
        clusterStore.putForAccount(nonDefaultAccount, "non-default-eks", legacyCluster);

        StorageFactory storageFactory = fixedStorageFactory(clusterStore);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(true), regionResolver, null,
                ec2Service, new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()), new ObjectMapper()), mock(EksAccessEntryService.class));

        assertDoesNotThrow(service::init);

        Cluster described = clusterStore.getForAccount(nonDefaultAccount, "non-default-eks")
                .orElseThrow();
        String sgId = described.getResourcesVpcConfig().getClusterSecurityGroupId();
        assertNotNull(sgId);
        assertTrue(sgId.startsWith("sg-"));
        assertEquals(nonDefaultAccount, described.getAccountId());
    }

    @Test
    void createClusterSecurityGroupCleansUpWhenTaggingFails() {
        Ec2Service spyEc2 = spy(realEc2Service());
        spyEc2.ensureDefaultResources("us-east-1");
        String defaultVpc = Ec2Service.defaultVpcId("us-east-1");

        doThrow(new RuntimeException("Simulated tagging failure"))
                .when(spyEc2).createTags(any(), any(), any());

        StorageFactory storageFactory = fixedStorageFactory(new InMemoryStorage<>());
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null,
                spyEc2, new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class));

        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setVpcId(defaultVpc);
        CreateClusterRequest req = new CreateClusterRequest();
        req.setName("cluster-tag-fail");
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setResourcesVpcConfig(vpcConfig);

        assertThrows(RuntimeException.class, () -> service.createCluster(req));

        List<SecurityGroup> allSgs = spyEc2.describeSecurityGroups("us-east-1", List.of(), List.of(), Map.of());
        boolean leaked = allSgs.stream().anyMatch(sg -> sg.getGroupName().startsWith("eks-cluster-sg-cluster-tag-fail-"));
        assertFalse(leaked, "Cluster security group should have been deleted when tagging failed");
    }

    private CreateClusterRequest createTestClusterRequest(String name) {
        CreateClusterRequest req = new CreateClusterRequest();
        req.setName(name);
        req.setRoleArn("arn:aws:iam::000000000000:role/eks-role");
        req.setVersion("1.29");
        return req;
    }

    @Test
    void createClusterWithEncryptionConfigAndLogging() {
        CreateClusterRequest req = createTestClusterRequest("enc-log-cluster");
        req.setEncryptionConfig(List.of(
                new EncryptionConfig(List.of("secrets"), new Provider("arn:aws:kms:us-east-1:000000000000:key/12345678-1234-1234-1234-123456789012"))
        ));
        req.setLogging(new Logging(List.of(
                new LogSetup(List.of("api", "audit"), true),
                new LogSetup(List.of("authenticator", "controllerManager", "scheduler"), false)
        )));

        Cluster created = eksService.createCluster(req);
        assertNotNull(created.getEncryptionConfig());
        assertEquals(1, created.getEncryptionConfig().size());
        assertEquals(List.of("secrets"), created.getEncryptionConfig().getFirst().getResources());
        assertEquals("arn:aws:kms:us-east-1:000000000000:key/12345678-1234-1234-1234-123456789012",
                created.getEncryptionConfig().getFirst().getProvider().getKeyArn());

        assertNotNull(created.getLogging());
        List<LogSetup> logSetups = created.getLogging().getClusterLogging();
        assertEquals(2, logSetups.size());
        assertEquals(List.of("api", "audit"), logSetups.get(0).getTypes());
        assertTrue(logSetups.get(0).getEnabled());
        assertEquals(List.of("authenticator", "controllerManager", "scheduler"), logSetups.get(1).getTypes());
        assertFalse(logSetups.get(1).getEnabled());

        Cluster described = eksService.describeCluster("enc-log-cluster");
        assertEquals(created.getEncryptionConfig(), described.getEncryptionConfig());
        assertEquals(created.getLogging(), described.getLogging());
    }

    @Test
    void createClusterWithoutEncryptionConfigOrLoggingUsesDefaults() {
        CreateClusterRequest req = createTestClusterRequest("default-cluster");

        Cluster created = eksService.createCluster(req);
        assertNull(created.getEncryptionConfig());

        assertNotNull(created.getLogging());
        List<LogSetup> logSetups = created.getLogging().getClusterLogging();
        assertEquals(1, logSetups.size());
        assertEquals(List.of("api", "audit", "authenticator", "controllerManager", "scheduler"),
                logSetups.getFirst().getTypes());
        assertFalse(logSetups.getFirst().getEnabled());

        Cluster described = eksService.describeCluster("default-cluster");
        assertNull(described.getEncryptionConfig());
        assertNotNull(described.getLogging());
        assertEquals(logSetups, described.getLogging().getClusterLogging());
    }

    @Test
    void createClusterWithPartiallyEnabledLoggingSplitsEntries() {
        CreateClusterRequest req = createTestClusterRequest("partial-log-cluster");
        req.setLogging(new Logging(List.of(
                new LogSetup(List.of("authenticator", "api"), true)
        )));

        Cluster created = eksService.createCluster(req);
        assertNotNull(created.getLogging());
        List<LogSetup> logSetups = created.getLogging().getClusterLogging();
        assertEquals(2, logSetups.size());
        assertEquals(List.of("api", "authenticator"), logSetups.get(0).getTypes());
        assertTrue(logSetups.get(0).getEnabled());
        assertEquals(List.of("audit", "controllerManager", "scheduler"), logSetups.get(1).getTypes());
        assertFalse(logSetups.get(1).getEnabled());
    }

    @Test
    void createClusterWithInvalidLogTypeThrowsInvalidParameterException() {
        CreateClusterRequest req = createTestClusterRequest("invalid-log-cluster");
        req.setLogging(new Logging(List.of(
                new LogSetup(List.of("nonexistentLogType"), true)
        )));

        AwsException ex = assertThrows(AwsException.class, () -> eksService.createCluster(req));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void createClusterWithInvalidEncryptionResourcesThrowsInvalidParameterException() {
        CreateClusterRequest req1 = createTestClusterRequest("invalid-enc-res-1");
        req1.setEncryptionConfig(List.of(
                new EncryptionConfig(List.of("configmaps"), new Provider("arn:aws:kms:us-east-1:000000000000:key/12345678"))
        ));
        AwsException ex1 = assertThrows(AwsException.class, () -> eksService.createCluster(req1));
        assertEquals("InvalidParameterException", ex1.getErrorCode());
        assertEquals(400, ex1.getHttpStatus());

        CreateClusterRequest req2 = createTestClusterRequest("invalid-enc-res-2");
        req2.setEncryptionConfig(List.of(
                new EncryptionConfig(List.of("Secrets"), new Provider("arn:aws:kms:us-east-1:000000000000:key/12345678"))
        ));
        AwsException ex2 = assertThrows(AwsException.class, () -> eksService.createCluster(req2));
        assertEquals("InvalidParameterException", ex2.getErrorCode());
        assertEquals(400, ex2.getHttpStatus());
    }

    @Test
    void createClusterWithMultipleEncryptionConfigsThrowsInvalidParameterException() {
        CreateClusterRequest req = createTestClusterRequest("multiple-enc-cluster");
        req.setEncryptionConfig(List.of(
                new EncryptionConfig(List.of("secrets"), new Provider("arn:aws:kms:us-east-1:000000000000:key/1")),
                new EncryptionConfig(List.of("secrets"), new Provider("arn:aws:kms:us-east-1:000000000000:key/2"))
        ));
        AwsException ex = assertThrows(AwsException.class, () -> eksService.createCluster(req));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void createClusterWithMissingEncryptionKeyArnThrowsInvalidParameterException() {
        CreateClusterRequest req1 = createTestClusterRequest("missing-keyarn-1");
        req1.setEncryptionConfig(List.of(
                new EncryptionConfig(List.of("secrets"), null)
        ));
        AwsException ex1 = assertThrows(AwsException.class, () -> eksService.createCluster(req1));
        assertEquals("InvalidParameterException", ex1.getErrorCode());
        assertEquals(400, ex1.getHttpStatus());

        CreateClusterRequest req2 = createTestClusterRequest("missing-keyarn-2");
        req2.setEncryptionConfig(List.of(
                new EncryptionConfig(List.of("secrets"), new Provider(""))
        ));
        AwsException ex2 = assertThrows(AwsException.class, () -> eksService.createCluster(req2));
        assertEquals("InvalidParameterException", ex2.getErrorCode());
        assertEquals(400, ex2.getHttpStatus());
    }

    @Test
    void backfillLoggingPopulatesDefaultLoggingForPreExistingClusters() {
        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        AccountAwareStorageBackend<Cluster> clusterStore =
                new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");

        Cluster legacyCluster = new Cluster();
        legacyCluster.setName("legacy-eks");
        legacyCluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/legacy-eks");
        legacyCluster.setAccountId("000000000000");
        legacyCluster.setStatus(ClusterStatus.ACTIVE);
        legacyCluster.setLogging(null);
        legacyCluster.setEncryptionConfig(null);
        clusterStore.putForAccount("000000000000", "legacy-eks", legacyCluster);

        StorageFactory storageFactory = fixedStorageFactory(clusterStore);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(true), regionResolver, null,
                realEc2Service(), new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()), new ObjectMapper()), mock(EksAccessEntryService.class));

        assertDoesNotThrow(service::init);

        Cluster described = service.describeCluster("legacy-eks");
        assertNotNull(described.getLogging());
        assertEquals(1, described.getLogging().getClusterLogging().size());
        assertEquals(List.of("api", "audit", "authenticator", "controllerManager", "scheduler"),
                described.getLogging().getClusterLogging().getFirst().getTypes());
        assertFalse(described.getLogging().getClusterLogging().getFirst().getEnabled());
        assertNull(described.getEncryptionConfig());
    }

    @Test
    void backfillLoggingPreservesExistingLogging() {
        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        AccountAwareStorageBackend<Cluster> clusterStore =
                new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");

        Cluster existingCluster = new Cluster();
        existingCluster.setName("custom-logging-eks");
        existingCluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/custom-logging-eks");
        existingCluster.setAccountId("000000000000");
        existingCluster.setStatus(ClusterStatus.ACTIVE);
        Logging customLogging = new Logging(List.of(new LogSetup(List.of("api"), true)));
        existingCluster.setLogging(customLogging);
        clusterStore.putForAccount("000000000000", "custom-logging-eks", existingCluster);

        StorageFactory storageFactory = fixedStorageFactory(clusterStore);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        EksService service = new EksService(storageFactory, testConfig(true), regionResolver, null,
                realEc2Service(), new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()), new ObjectMapper()), mock(EksAccessEntryService.class));

        assertDoesNotThrow(service::init);

        Cluster described = service.describeCluster("custom-logging-eks");
        assertEquals(customLogging, described.getLogging());
    }

    @Test
    void restartRoundTripPreservesEncryptionConfigAndLogging() {
        StorageBackend<String, Cluster> rawClusters = new InMemoryStorage<>();
        AccountAwareStorageBackend<Cluster> clusterStore =
                new AccountAwareStorageBackend<>(rawClusters, null, "000000000000");
        StorageFactory storageFactory = fixedStorageFactory(clusterStore);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");

        EksService service1 = new EksService(storageFactory, testConfig(true), regionResolver, null,
                realEc2Service(), new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()), new ObjectMapper()), mock(EksAccessEntryService.class));
        service1.init();

        CreateClusterRequest req = createTestClusterRequest("restart-cluster");
        req.setEncryptionConfig(List.of(
                new EncryptionConfig(List.of("secrets"), new Provider("arn:aws:kms:us-east-1:000000000000:key/persist-key"))
        ));
        req.setLogging(new Logging(List.of(
                new LogSetup(List.of("api", "audit"), true)
        )));
        Cluster created = service1.createCluster(req);

        EksService service2 = new EksService(storageFactory, testConfig(true), regionResolver, null,
                realEc2Service(), new EksOidcService(fixedStorageFactory(new InMemoryStorage<String, ClusterOidcKey>()), new ObjectMapper()), mock(EksAccessEntryService.class));
        service2.init();

        Cluster described = service2.describeCluster("restart-cluster");
        assertEquals(created.getEncryptionConfig(), described.getEncryptionConfig());
        assertEquals(created.getLogging(), described.getLogging());
    }

    @Test
    void createClusterWithSupportedVersion() {
        CreateClusterRequest req = createTestClusterRequest("v30-cluster");
        req.setVersion("1.30");

        Cluster created = eksService.createCluster(req);
        assertEquals("1.30", created.getVersion());

        Cluster described = eksService.describeCluster("v30-cluster");
        assertEquals("1.30", described.getVersion());
    }

    @Test
    void createClusterWithFutureVersionSucceeds() {
        CreateClusterRequest req = createTestClusterRequest("v37-cluster");
        req.setVersion("1.37");

        Cluster created = eksService.createCluster(req);
        assertEquals("1.37", created.getVersion());

        Cluster described = eksService.describeCluster("v37-cluster");
        assertEquals("1.37", described.getVersion());
    }

    @Test
    void createClusterWithoutVersionDefaultsTo129() {
        CreateClusterRequest req = createTestClusterRequest("default-ver-cluster");
        req.setVersion(null);
        Cluster created = eksService.createCluster(req);
        assertEquals("1.29", created.getVersion());
        assertFalse(created.isExplicitVersion());
    }

    @Test
    void explicitVersionPreservedAcrossRestart() throws Exception {
        CreateClusterRequest explicitReq = createTestClusterRequest("explicit-129-cluster");
        explicitReq.setVersion("1.29");
        Cluster createdExplicit = eksService.createCluster(explicitReq);
        assertTrue(createdExplicit.isExplicitVersion());

        CreateClusterRequest defaultReq = createTestClusterRequest("default-129-cluster");
        defaultReq.setVersion(null);
        Cluster createdDefault = eksService.createCluster(defaultReq);
        assertFalse(createdDefault.isExplicitVersion());

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Cluster reloadedExplicit = mapper.readValue(mapper.writeValueAsString(createdExplicit), Cluster.class);
        assertTrue(reloadedExplicit.isExplicitVersion());
        assertEquals("1.29", reloadedExplicit.getVersion());

        Cluster reloadedDefault = mapper.readValue(mapper.writeValueAsString(createdDefault), Cluster.class);
        assertFalse(reloadedDefault.isExplicitVersion());
        assertEquals("1.29", reloadedDefault.getVersion());
    }

    @Test
    void clusterCopyClearsExplicitVersionForWireResponse() throws Exception {
        CreateClusterRequest explicitReq = createTestClusterRequest("wire-explicit-cluster");
        explicitReq.setVersion("1.29");
        Cluster explicitCluster = eksService.createCluster(explicitReq);
        assertTrue(explicitCluster.isExplicitVersion());

        Cluster responseCopy = explicitCluster.copy();
        responseCopy.setExplicitVersion(false);
        assertFalse(responseCopy.isExplicitVersion());
        assertEquals("wire-explicit-cluster", responseCopy.getName());
        assertEquals("1.29", responseCopy.getVersion());

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(responseCopy);
        assertFalse(json.contains("explicitVersion"));
        assertTrue(json.contains("\"version\":\"1.29\""));
    }

    @Test
    void createClusterWithInvalidVersionFormatThrowsInvalidParameterException() {
        CreateClusterRequest req1 = createTestClusterRequest("v-prefix-cluster");
        req1.setVersion("v1.30");
        AwsException ex1 = assertThrows(AwsException.class, () -> eksService.createCluster(req1));
        assertEquals("InvalidParameterException", ex1.getErrorCode());
        assertTrue(ex1.getMessage().contains("The specified parameter version is not valid: v1.30"));

        CreateClusterRequest req2 = createTestClusterRequest("patch-version-cluster");
        req2.setVersion("1.30.2");
        AwsException ex2 = assertThrows(AwsException.class, () -> eksService.createCluster(req2));
        assertEquals("InvalidParameterException", ex2.getErrorCode());
        assertTrue(ex2.getMessage().contains("The specified parameter version is not valid: 1.30.2"));
    }

    @Test
    void createClusterWithUnsupportedVersionThrowsInvalidParameterException() {
        CreateClusterRequest req = createTestClusterRequest("bad-version-cluster");
        req.setVersion("1.15");

        AwsException ex = assertThrows(AwsException.class, () -> eksService.createCluster(req));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("Unsupported Kubernetes version '1.15'"));
    }

    @Test
    void createClusterWithCustomServiceIpv4Cidr() {
        CreateClusterRequest req = createTestClusterRequest("cidr-cluster");
        KubernetesNetworkConfig netConfig = new KubernetesNetworkConfig();
        netConfig.setServiceIpv4Cidr("172.20.0.0/16");
        req.setKubernetesNetworkConfig(netConfig);

        Cluster created = eksService.createCluster(req);
        assertNotNull(created.getKubernetesNetworkConfig());
        assertEquals("172.20.0.0/16", created.getKubernetesNetworkConfig().getServiceIpv4Cidr());

        Cluster described = eksService.describeCluster("cidr-cluster");
        assertEquals("172.20.0.0/16", described.getKubernetesNetworkConfig().getServiceIpv4Cidr());
    }

    @Test
    void createClusterWithInvalidServiceIpv4CidrThrowsInvalidParameterException() {
        CreateClusterRequest req = createTestClusterRequest("bad-cidr-cluster");
        KubernetesNetworkConfig netConfig = new KubernetesNetworkConfig();
        netConfig.setServiceIpv4Cidr("invalid-cidr");
        req.setKubernetesNetworkConfig(netConfig);

        AwsException ex = assertThrows(AwsException.class, () -> eksService.createCluster(req));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("kubernetesNetworkConfig.serviceIpv4Cidr is not valid"));
    }

    @Test
    void createClusterWithNonRfc1918ServiceIpv4CidrThrowsInvalidParameterException() {
        CreateClusterRequest req = createTestClusterRequest("public-ip-cluster");
        KubernetesNetworkConfig netConfig = new KubernetesNetworkConfig();
        netConfig.setServiceIpv4Cidr("8.8.8.0/24");
        req.setKubernetesNetworkConfig(netConfig);

        AwsException ex = assertThrows(AwsException.class, () -> eksService.createCluster(req));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("must fall within RFC 1918 private address ranges"));
    }

    @Test
    void createClusterWithInvalidPrefixServiceIpv4CidrThrowsInvalidParameterException() {
        CreateClusterRequest req1 = createTestClusterRequest("wide-cidr-cluster");
        KubernetesNetworkConfig netConfig1 = new KubernetesNetworkConfig();
        netConfig1.setServiceIpv4Cidr("10.0.0.0/8");
        req1.setKubernetesNetworkConfig(netConfig1);

        AwsException ex1 = assertThrows(AwsException.class, () -> eksService.createCluster(req1));
        assertEquals("InvalidParameterException", ex1.getErrorCode());
        assertTrue(ex1.getMessage().contains("must have a prefix between /12 and /24"));

        CreateClusterRequest req2 = createTestClusterRequest("narrow-cidr-cluster");
        KubernetesNetworkConfig netConfig2 = new KubernetesNetworkConfig();
        netConfig2.setServiceIpv4Cidr("10.0.0.0/28");
        req2.setKubernetesNetworkConfig(netConfig2);

        AwsException ex2 = assertThrows(AwsException.class, () -> eksService.createCluster(req2));
        assertEquals("InvalidParameterException", ex2.getErrorCode());
        assertTrue(ex2.getMessage().contains("must have a prefix between /12 and /24"));
    }

    @Test
    void createClusterUsesStandardK3sPodCidrToAvoidDockerCollisions() {
        Ec2Service ec2 = realEc2Service();
        ec2.ensureDefaultResources("us-east-1");
        String vpcId = ec2.createVpc("us-east-1", "172.31.0.0/16", false).getVpcId();
        String subnetId = ec2.createSubnet("us-east-1", vpcId, "172.31.1.0/24", "us-east-1a").getSubnetId();

        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null, ec2,
                new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class),
                mock(EksPodIdentityAssociationService.class));

        CreateClusterRequest req = createTestClusterRequest("vpc-cidr-cluster");
        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(List.of(subnetId));
        req.setResourcesVpcConfig(vpcConfig);

        Cluster cluster = service.createCluster(req);
        assertEquals("10.42.0.0/16", cluster.getPodCidr());
    }

    @Test
    void createClusterWithOverlappingServiceIpv4CidrThrowsInvalidParameterException() {
        Ec2Service ec2 = realEc2Service();
        ec2.ensureDefaultResources("us-east-1");
        String vpcId = ec2.createVpc("us-east-1", "10.0.0.0/16", false).getVpcId();
        String subnetId = ec2.createSubnet("us-east-1", vpcId, "10.0.1.0/24", "us-east-1a").getSubnetId();

        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null, ec2,
                new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class),
                mock(EksPodIdentityAssociationService.class));

        CreateClusterRequest req = createTestClusterRequest("overlap-cidr-cluster");
        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(List.of(subnetId));
        req.setResourcesVpcConfig(vpcConfig);

        KubernetesNetworkConfig netConfig = new KubernetesNetworkConfig();
        netConfig.setServiceIpv4Cidr("10.0.50.0/24");
        req.setKubernetesNetworkConfig(netConfig);

        AwsException ex = assertThrows(AwsException.class, () -> service.createCluster(req));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("overlaps with the VPC CIDR"));
    }

    @Test
    void createClusterWithDefaultServiceCidrOverlappingVpcPicksAlternative() {
        Ec2Service ec2 = realEc2Service();
        ec2.ensureDefaultResources("us-east-1");
        String vpcId = ec2.createVpc("us-east-1", "10.100.0.0/16", false).getVpcId();
        String subnetId = ec2.createSubnet("us-east-1", vpcId, "10.100.1.0/24", "us-east-1a").getSubnetId();

        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null, ec2,
                new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class),
                mock(EksPodIdentityAssociationService.class));

        CreateClusterRequest req = createTestClusterRequest("alt-cidr-cluster");
        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(List.of(subnetId));
        req.setResourcesVpcConfig(vpcConfig);

        Cluster cluster = service.createCluster(req);
        assertEquals(EksService.ALTERNATIVE_SERVICE_IPV4_CIDR, cluster.getKubernetesNetworkConfig().getServiceIpv4Cidr());
    }

    @Test
    void createClusterWithBothDefaultServiceCidrsOverlappingVpcThrowsInvalidParameterException() {
        Ec2Service ec2 = realEc2Service();
        ec2.ensureDefaultResources("us-east-1");
        String vpcId = ec2.createVpc("us-east-1", "0.0.0.0/0", false).getVpcId();
        String subnetId = ec2.createSubnet("us-east-1", vpcId, "0.0.0.0/24", "us-east-1a").getSubnetId();

        EksService service = new EksService(storageFactory, testConfig(), regionResolver, null, ec2,
                new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class),
                mock(EksPodIdentityAssociationService.class));

        CreateClusterRequest req = createTestClusterRequest("both-overlap-cluster");
        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(List.of(subnetId));
        req.setResourcesVpcConfig(vpcConfig);

        AwsException ex = assertThrows(AwsException.class, () -> service.createCluster(req));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("Default service IPv4 CIDR blocks"));
    }

    @Test
    void createClusterWithoutVpcDefaultsToK3sPodCidr() {
        CreateClusterRequest req = createTestClusterRequest("no-vpc-cluster");
        Cluster cluster = eksService.createCluster(req);
        assertEquals("10.42.0.0/16", cluster.getPodCidr());
    }

    private static final Map<String, Object> LAUNCH_TEMPLATE = Map.of(
            "name", "my-node-launch-template",
            "version", "3");

    private static final Map<String, Object> REMOTE_ACCESS = Map.of(
            "ec2SshKey", "my-keypair",
            "sourceSecurityGroups", List.of("sg-0123456789abcdef0", "sg-0fedcba9876543210"));

    private static final List<Object> TAINTS = List.of(
            Map.of("key", "dedicated", "value", "gpu", "effect", "NO_SCHEDULE"),
            Map.of("key", "spot", "value", "true", "effect", "PREFER_NO_SCHEDULE"));

    private static final Map<String, Object> NODE_REPAIR_CONFIG = Map.of(
            "enabled", true,
            "maxUnhealthyNodeThresholdPercentage", 20,
            "maxParallelNodesRepairedCount", 2);

    private static final Map<String, Object> WARM_POOL_CONFIG = Map.of(
            "enabled", true,
            "minSize", 2,
            "maxGroupPreparedCapacity", 5,
            "poolState", "Stopped",
            "reuseOnScaleIn", false);

    private CreateNodeGroupRequest nodeGroupRequestWithStructuredInputs(String name) {
        CreateNodeGroupRequest request = nodeGroupRequest(name);
        request.setLaunchTemplate(LAUNCH_TEMPLATE);
        request.setRemoteAccess(REMOTE_ACCESS);
        request.setTaints(TAINTS);
        request.setNodeRepairConfig(NODE_REPAIR_CONFIG);
        request.setWarmPoolConfig(WARM_POOL_CONFIG);
        return request;
    }

    private void assertStructuredInputsEchoed(Nodegroup nodeGroup) {
        assertEquals(LAUNCH_TEMPLATE, nodeGroup.getLaunchTemplate());
        assertEquals(REMOTE_ACCESS, nodeGroup.getRemoteAccess());
        assertEquals(TAINTS, nodeGroup.getTaints());
        assertEquals(NODE_REPAIR_CONFIG, nodeGroup.getNodeRepairConfig());
        assertEquals(WARM_POOL_CONFIG, nodeGroup.getWarmPoolConfig());
    }

    @Test
    void createNodeGroupStoresStructuredInputs() {
        createTestCluster("my-eks-cluster");

        Nodegroup created = eksService.createNodeGroup("my-eks-cluster",
                nodeGroupRequestWithStructuredInputs("structured-ng"));

        assertStructuredInputsEchoed(created);
    }

    @Test
    void describeNodeGroupReturnsStructuredInputsWithNestedFieldsIntact() {
        createTestCluster("my-eks-cluster");
        eksService.createNodeGroup("my-eks-cluster", nodeGroupRequestWithStructuredInputs("structured-ng"));

        Nodegroup described = eksService.describeNodeGroup("my-eks-cluster", "structured-ng");

        assertStructuredInputsEchoed(described);
        // The nested members are what OpenTofu diffs against its declared block, so spot-check
        // them individually rather than trusting the whole-map comparison alone.
        Map<?, ?> launchTemplate = (Map<?, ?>) described.getLaunchTemplate();
        assertNull(launchTemplate.get("id"));
        assertEquals("my-node-launch-template", launchTemplate.get("name"));
        assertEquals("3", launchTemplate.get("version"));
        Map<?, ?> remoteAccess = (Map<?, ?>) described.getRemoteAccess();
        assertEquals("my-keypair", remoteAccess.get("ec2SshKey"));
        assertEquals(List.of("sg-0123456789abcdef0", "sg-0fedcba9876543210"),
                remoteAccess.get("sourceSecurityGroups"));
        Map<?, ?> firstTaint = (Map<?, ?>) described.getTaints().getFirst();
        assertEquals("dedicated", firstTaint.get("key"));
        assertEquals("gpu", firstTaint.get("value"));
        assertEquals("NO_SCHEDULE", firstTaint.get("effect"));
        assertEquals(20, ((Map<?, ?>) described.getNodeRepairConfig())
                .get("maxUnhealthyNodeThresholdPercentage"));
        assertEquals("Stopped", ((Map<?, ?>) described.getWarmPoolConfig()).get("poolState"));
    }

    @Test
    void createNodeGroupWithoutStructuredInputsOmitsThemInsteadOfEmittingNulls() throws Exception {
        createTestCluster("my-eks-cluster");
        eksService.createNodeGroup("my-eks-cluster", nodeGroupRequest("bare-ng"));

        Nodegroup described = eksService.describeNodeGroup("my-eks-cluster", "bare-ng");
        assertNull(described.getLaunchTemplate());
        assertNull(described.getRemoteAccess());
        assertNull(described.getTaints());
        assertNull(described.getNodeRepairConfig());
        assertNull(described.getWarmPoolConfig());

        // An explicit null on the wire is drift in its own right: OpenTofu reads it as "the remote
        // value is set to nothing" rather than "unset", so the keys must be absent entirely.
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Map<String, Object> wire = mapper.readValue(mapper.writeValueAsString(described),
                new TypeReference<Map<String, Object>>() {
                });
        assertFalse(wire.containsKey("launchTemplate"));
        assertFalse(wire.containsKey("remoteAccess"));
        assertFalse(wire.containsKey("taints"));
        assertFalse(wire.containsKey("nodeRepairConfig"));
        assertFalse(wire.containsKey("warmPoolConfig"));
    }

    @Test
    void nodeGroupStructuredInputsSurviveRestart(@TempDir Path directory) {
        EksService before = persistentEksService(directory);
        before.init();
        before.createCluster(createTestClusterRequest("restart-ng-cluster"));
        Nodegroup created = before.createNodeGroup("restart-ng-cluster",
                nodeGroupRequestWithStructuredInputs("restart-ng"));

        EksService after = persistentEksService(directory);
        after.init();

        Nodegroup described = after.describeNodeGroup("restart-ng-cluster", "restart-ng");
        assertStructuredInputsEchoed(described);
        assertEquals(created.getLaunchTemplate(), described.getLaunchTemplate());
        assertEquals(created.getTaints(), described.getTaints());
    }

    @Test
    void createNodeGroupWithValidLaunchTemplateIdAndVersionSucceeds() {
        createTestCluster("lt-cluster");
        LaunchTemplate lt = ec2Service.createLaunchTemplate("us-east-1", "test-lt-id",
                new LaunchTemplateData(), null, null);

        CreateNodeGroupRequest request = nodeGroupRequest("valid-lt-id-ng");
        request.setLaunchTemplate(Map.of("id", lt.getLaunchTemplateId(), "version", "1"));

        Nodegroup created = eksService.createNodeGroup("lt-cluster", request);
        assertNotNull(created);
        assertEquals(Map.of("id", lt.getLaunchTemplateId(), "version", "1"), created.getLaunchTemplate());
    }

    @Test
    void createNodeGroupWithValidLaunchTemplateNameAndVersionSucceeds() {
        createTestCluster("lt-cluster");
        ec2Service.createLaunchTemplate("us-east-1", "test-lt-name",
                new LaunchTemplateData(), null, null);

        CreateNodeGroupRequest request = nodeGroupRequest("valid-lt-name-ng");
        request.setLaunchTemplate(Map.of("name", "test-lt-name", "version", "1"));

        Nodegroup created = eksService.createNodeGroup("lt-cluster", request);
        assertNotNull(created);
        assertEquals(Map.of("name", "test-lt-name", "version", "1"), created.getLaunchTemplate());
    }

    @Test
    void createNodeGroupWithValidLaunchTemplateWithoutVersionDefaultsVersion() {
        createTestCluster("lt-cluster");
        LaunchTemplate lt = ec2Service.createLaunchTemplate("us-east-1", "test-lt-default-ver",
                new LaunchTemplateData(), null, null);

        CreateNodeGroupRequest request = nodeGroupRequest("default-ver-ng");
        request.setLaunchTemplate(Map.of("id", lt.getLaunchTemplateId()));

        Nodegroup created = eksService.createNodeGroup("lt-cluster", request);
        assertNotNull(created);
    }

    @Test
    void createNodeGroupWithNonExistentLaunchTemplateIdFails() {
        createTestCluster("lt-cluster");
        CreateNodeGroupRequest request = nodeGroupRequest("missing-lt-id-ng");
        request.setLaunchTemplate(Map.of("id", "lt-missing123456789", "version", "1"));

        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createNodeGroup("lt-cluster", request));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("Launch template could not be found"));
    }

    @Test
    void createNodeGroupWithNonExistentLaunchTemplateNameFails() {
        createTestCluster("lt-cluster");
        CreateNodeGroupRequest request = nodeGroupRequest("missing-lt-name-ng");
        request.setLaunchTemplate(Map.of("name", "non-existent-template", "version", "1"));

        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createNodeGroup("lt-cluster", request));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("Launch template could not be found"));
    }

    @Test
    void createNodeGroupWithNonExistentLaunchTemplateVersionFails() {
        createTestCluster("lt-cluster");
        LaunchTemplate lt = ec2Service.createLaunchTemplate("us-east-1", "test-lt-missing-ver",
                new LaunchTemplateData(), null, null);

        CreateNodeGroupRequest request = nodeGroupRequest("missing-ver-ng");
        request.setLaunchTemplate(Map.of("id", lt.getLaunchTemplateId(), "version", "99"));

        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createNodeGroup("lt-cluster", request));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertEquals("The specified launch template version does not exist.", ex.getMessage());
    }

    @Test
    void createNodeGroupWithMalformedLaunchTemplateVersionFails() {
        createTestCluster("lt-cluster");
        LaunchTemplate lt = ec2Service.createLaunchTemplate("us-east-1", "test-lt-malformed-ver",
                new LaunchTemplateData(), null, null);

        CreateNodeGroupRequest request = nodeGroupRequest("malformed-ver-ng");
        request.setLaunchTemplate(Map.of("id", lt.getLaunchTemplateId(), "version", "not-a-number"));

        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createNodeGroup("lt-cluster", request));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertEquals("The specified launch template version is not valid.", ex.getMessage());
    }

    @Test
    void createNodeGroupUsesClusterRegionForArnAndLaunchTemplate() {
        Cluster cluster = new Cluster();
        cluster.setName("regional-cluster");
        cluster.setArn("arn:aws:eks:eu-west-1:000000000000:cluster/regional-cluster");
        cluster.setStatus(ClusterStatus.ACTIVE);
        eksService.putClusterForAccount("000000000000", cluster);

        LaunchTemplate lt = ec2Service.createLaunchTemplate("eu-west-1", "eu-lt",
                new LaunchTemplateData(), null, null);

        CreateNodeGroupRequest request = nodeGroupRequest("regional-ng");
        request.setLaunchTemplate(Map.of("id", lt.getLaunchTemplateId(), "version", "1"));

        Nodegroup created = eksService.createNodeGroup("regional-cluster", request);
        assertNotNull(created);
        assertTrue(created.getNodegroupArn().startsWith(
                "arn:aws:eks:eu-west-1:000000000000:nodegroup/regional-cluster/regional-ng/"));
    }

    @Test
    void createNodeGroupValidatesLaunchTemplateInClusterRegion() {
        Cluster cluster = new Cluster();
        cluster.setName("eu-cluster");
        cluster.setArn("arn:aws:eks:eu-west-1:000000000000:cluster/eu-cluster");
        cluster.setStatus(ClusterStatus.ACTIVE);
        eksService.putClusterForAccount("000000000000", cluster);

        LaunchTemplate lt = ec2Service.createLaunchTemplate("us-east-1", "us-only-lt",
                new LaunchTemplateData(), null, null);

        CreateNodeGroupRequest request = nodeGroupRequest("eu-ng");
        request.setLaunchTemplate(Map.of("id", lt.getLaunchTemplateId(), "version", "1"));

        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createNodeGroup("eu-cluster", request));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("Launch template could not be found"));
    }

    @Test
    void createNodeGroupWithBothLaunchTemplateIdAndNameFails() {
        createTestCluster("lt-cluster");
        LaunchTemplate lt = ec2Service.createLaunchTemplate("us-east-1", "test-lt-both",
                new LaunchTemplateData(), null, null);

        CreateNodeGroupRequest request = nodeGroupRequest("both-id-name-ng");
        request.setLaunchTemplate(Map.of(
                "id", lt.getLaunchTemplateId(),
                "name", lt.getLaunchTemplateName(),
                "version", "1"));

        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createNodeGroup("lt-cluster", request));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertEquals("You must specify either the launch template ID or the launch template name in the request, but not both.",
                ex.getMessage());
    }

    @Test
    void createNodeGroupWithNeitherLaunchTemplateIdNorNameFails() {
        createTestCluster("lt-cluster");
        CreateNodeGroupRequest request = nodeGroupRequest("neither-id-name-ng");
        request.setLaunchTemplate(Map.of("version", "1"));

        AwsException ex = assertThrows(AwsException.class,
                () -> eksService.createNodeGroup("lt-cluster", request));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertEquals("You must specify either the launch template ID or the launch template name in the request, but not both.",
                ex.getMessage());
    }

    @Test
    void createNodeGroupWithNoLaunchTemplateUnaffected() {
        createTestCluster("lt-cluster");
        CreateNodeGroupRequest request = nodeGroupRequest("no-lt-ng");
        request.setLaunchTemplate(null);

        Nodegroup created = eksService.createNodeGroup("lt-cluster", request);
        assertNotNull(created);
        assertNull(created.getLaunchTemplate());
    }

    /**
     * A service whose stores are real JSON files under {@code directory}, one per store name, so a
     * second instance over the same directory reloads through Jackson exactly as a restart does.
     */
    private EksService persistentEksService(Path directory) {
        return persistentEksService(directory, ec2Service);
    }

    private EksService persistentEksService(Path directory, Ec2Service ec2) {
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                PersistentStorage<String, V> backend =
                        new PersistentStorage<>(directory.resolve(fileName), typeReference);
                backend.load();
                return new AccountAwareStorageBackend<>(backend, null, "000000000000");
            }
        };
        return new EksService(storageFactory, testConfig(true),
                new RegionResolver("us-east-1", "000000000000"), null, ec2,
                new EksOidcService(storageFactory, new ObjectMapper()), mock(EksAccessEntryService.class),
                mock(EksPodIdentityAssociationService.class));
    }
}

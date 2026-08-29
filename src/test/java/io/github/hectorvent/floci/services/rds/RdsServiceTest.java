package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.container.RdsContainerHandle;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.rds.model.DbInstanceSettings;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbProxy;
import io.github.hectorvent.floci.services.rds.model.DbProxyAuth;
import io.github.hectorvent.floci.services.rds.model.DbProxyTarget;
import io.github.hectorvent.floci.services.rds.model.DbProxyTargetGroup;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
import io.github.hectorvent.floci.services.rds.model.OptionGroup;
import io.github.hectorvent.floci.services.rds.model.OptionGroupOption;
import io.github.hectorvent.floci.services.rds.proxy.RdsAuthProxy;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyManager;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.OptionalInt;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdsServiceTest {

    private static final String PROXY_ROLE_ARN = "arn:aws:iam::123456789012:role/proxy";
    private static final List<String> PROXY_SUBNET_IDS =
            List.of("subnet-default-a", "subnet-default-b");
    private static final List<DbProxyAuth> PROXY_AUTH = List.of(new DbProxyAuth(
            "SECRETS", "arn:aws:secretsmanager:us-east-1:123456789012:secret:db-AbCdEf",
            "DISABLED", null, null));
    private static final List<String> CURRENT_MANAGED_CLUSTER_PARAMETER_GROUP_FAMILIES = List.of(
            "aurora-mysql5.7",
            "aurora-mysql8.0",
            "aurora-mysql8.4",
            "aurora-postgresql11",
            "aurora-postgresql12",
            "aurora-postgresql13",
            "aurora-postgresql14",
            "aurora-postgresql15",
            "aurora-postgresql16",
            "aurora-postgresql17",
            "aurora-postgresql18",
            "mysql8.0",
            "mysql8.4",
            "postgres13",
            "postgres14",
            "postgres15",
            "postgres16",
            "postgres17",
            "postgres18",
            "docdb3.6",
            "docdb4.0",
            "docdb5.0",
            "docdb8.0");

    private RdsService rdsService;
    private RdsContainerManager containerManager;
    private RdsProxyManager proxyManager;
    private Ec2Service ec2Service;
    private RegionResolver regionResolver;
    private KmsService kmsService;
    private EmulatorConfig config;
    private EmulatorConfig.RdsServiceConfig rdsConfig;

    @BeforeEach
    void setUp() {
        containerManager = mock(RdsContainerManager.class);
        proxyManager = mock(RdsProxyManager.class);
        ec2Service = mock(Ec2Service.class);
        regionResolver = new RegionResolver("us-east-1", "123456789012");
        kmsService = mock(KmsService.class);
        when(kmsService.describeKey(any(), any())).thenThrow(
                new AwsException("NotFoundException", "Key not found", 404));
        config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        rdsConfig = mock(EmulatorConfig.RdsServiceConfig.class);

        when(config.services()).thenReturn(servicesConfig);
        when(config.defaultAccountId()).thenReturn("123456789012");
        when(servicesConfig.rds()).thenReturn(rdsConfig);
        when(rdsConfig.proxyBasePort()).thenReturn(7000);
        when(rdsConfig.proxyMaxPort()).thenReturn(7099);
        when(rdsConfig.defaultPostgresImage()).thenReturn(Optional.empty());
        when(rdsConfig.defaultMysqlImage()).thenReturn(Optional.empty());
        when(rdsConfig.defaultMariadbImage()).thenReturn(Optional.empty());

        rdsService = newService(containerManager, proxyManager,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());

        when(containerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("cont-id", "id", "localhost", 5432));
        when(ec2Service.resolveDefaultVpcId(any()))
                .thenAnswer(invocation -> Ec2Service.defaultVpcId(invocation.getArgument(0)));
        when(ec2Service.describeSubnets(any(), anyList(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<String> subnetIds = invocation.getArgument(1, List.class);
                    if (subnetIds == null || subnetIds.isEmpty()) {
                        return defaultSubnets();
                    }
                    Map<String, Subnet> byId = defaultSubnets().stream()
                            .collect(Collectors.toMap(Subnet::getSubnetId, subnet -> subnet));
                    return subnetIds.stream()
                            .map(byId::get)
                            .filter(java.util.Objects::nonNull)
                            .toList();
                });
    }

    @Test
    void createDbInstanceGeneratesMissingFields() {
        DbInstance instance = rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);

        assertEquals("mydb", instance.getDbInstanceIdentifier());
        assertNotNull(instance.getDbiResourceId());
        assertTrue(instance.getDbiResourceId().startsWith("db-"));
        assertEquals("arn:aws:rds:us-east-1:123456789012:db:mydb", instance.getDbInstanceArn());
    }

    @Test
    void createAndModifyDbInstancePersistVpcSecurityGroups() {
        DbInstance instance = rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of("sg-created"));

        assertEquals(List.of("sg-created"), instance.getVpcSecurityGroupIds());

        DbInstance modified = rdsService.modifyDbInstance("mydb", null, null, null,
                List.of("sg-updated-a", "sg-updated-b"));

        assertEquals(List.of("sg-updated-a", "sg-updated-b"), modified.getVpcSecurityGroupIds());
        assertEquals(List.of("sg-updated-a", "sg-updated-b"), rdsService.getDbInstance("mydb").getVpcSecurityGroupIds());
    }

    @Test
    void postgresImageUsesRequestedEngineVersionAndDefaultFlavor() {
        assertEquals("postgres:18.1-alpine",
                RdsService.imageForRequestedVersion("postgres:16-alpine", "18.1"));
        assertEquals("example.com/library/postgres:18.1-alpine",
                RdsService.imageForRequestedVersion("example.com/library/postgres:16-alpine", "18.1"));
        assertEquals("postgres:18.1",
                RdsService.imageForRequestedVersion("postgres", "18.1"));
        assertEquals("postgres:18.1-alpine",
                RdsService.imageForRequestedVersion("postgres:16-alpine", "18.1-alpine"));
    }

    @Test
    void createDbClusterRejectsADuplicateIdentifier() {
        rdsService.createDbCluster("dup-cluster", "postgres", "17.5",
                "admin", "password", "dbname", false, null, null, null, false);

        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbCluster("dup-cluster", "postgres", "17.5",
                        "admin", "password", "dbname", false, null, null, null, false));
        assertEquals("DBClusterAlreadyExistsFault", exception.getErrorCode());
        verify(containerManager, times(1)).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A client-side retry that arrives while the first CreateDBCluster is still
     * pulling the image used to pass the exists-check (the cluster is registered
     * only after provisioning) and start a second backing container, silently
     * splitting the cluster and instance endpoints across two databases. The
     * duplicate must fail fast instead, and only one container may ever start.
     */
    @Test
    void createDbClusterRejectsADuplicateWhileTheFirstIsStillProvisioning() throws Exception {
        CountDownLatch insideStart = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        when(containerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    insideStart.countDown();
                    assertTrue(releaseStart.await(5, TimeUnit.SECONDS), "test released the latch");
                    return new RdsContainerHandle("cont-id", "id", "localhost", 5432);
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DbCluster> first = executor.submit(() -> rdsService.createDbCluster(
                    "racy-cluster", "postgres", "17.5", "admin", "password", "dbname",
                    false, null, null, null, false));
            assertTrue(insideStart.await(5, TimeUnit.SECONDS), "first create reached provisioning");

            AwsException fault = assertThrows(AwsException.class, () ->
                    rdsService.createDbCluster("racy-cluster", "postgres", "17.5",
                            "admin", "password", "dbname", false, null, null, null, false));
            assertEquals("DBClusterAlreadyExistsFault", fault.getErrorCode());
            assertEquals(400, fault.getHttpStatus());

            releaseStart.countDown();
            assertNotNull(first.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        verify(containerManager, times(1)).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertNotNull(rdsService.getDbCluster("racy-cluster"));
    }

    @Test
    void createDbInstanceRejectsADuplicateWhileTheFirstIsStillProvisioning() throws Exception {
        CountDownLatch insideStart = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        when(containerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    insideStart.countDown();
                    assertTrue(releaseStart.await(5, TimeUnit.SECONDS), "test released the latch");
                    return new RdsContainerHandle("cont-id", "id", "localhost", 5432);
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DbInstance> first = executor.submit(() -> rdsService.createDbInstance(
                    "racy-db", "postgres", "17.5", "admin", "password", "dbname",
                    "db.t3.micro", 20, false, null, null, null, null, false));
            assertTrue(insideStart.await(5, TimeUnit.SECONDS), "first create reached provisioning");

            AwsException fault = assertThrows(AwsException.class, () ->
                    rdsService.createDbInstance("racy-db", "postgres", "17.5",
                            "admin", "password", "dbname", "db.t3.micro",
                            20, false, null, null, null, null, false));
            assertEquals("DBInstanceAlreadyExists", fault.getErrorCode());
            assertEquals(400, fault.getHttpStatus());

            releaseStart.countDown();
            assertNotNull(first.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        verify(containerManager, times(1)).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertNotNull(rdsService.getDbInstance("racy-db"));
    }

    /**
     * The issue's own repro provisions a cluster and an instance under one
     * identifier — the two live in separate namespaces, so the guard must not
     * let an in-flight cluster create block an instance create of the same name.
     */
    @Test
    void clusterAndInstanceMayShareAnIdentifierEvenWhileProvisioning() throws Exception {
        CountDownLatch insideStart = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        when(containerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    if (firstCall.getAndSet(false)) {
                        insideStart.countDown();
                        assertTrue(releaseStart.await(5, TimeUnit.SECONDS), "test released the latch");
                    }
                    return new RdsContainerHandle("cont-id", "id", "localhost", 5432);
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DbCluster> cluster = executor.submit(() -> rdsService.createDbCluster(
                    "shared-id", "postgres", "17.5", "admin", "password", "dbname",
                    false, null, null, null, false));
            assertTrue(insideStart.await(5, TimeUnit.SECONDS), "cluster create reached provisioning");

            assertNotNull(rdsService.createDbInstance("shared-id", "postgres", "17.5",
                    "admin", "password", "dbname", "db.t3.micro",
                    20, false, null, null, null, null, false));

            releaseStart.countDown();
            assertNotNull(cluster.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    /** A successful create must release the identifier too — delete then recreate reuses it. */
    @Test
    void deleteThenRecreateReusesTheIdentifier() {
        assertNotNull(rdsService.createDbCluster("reused-cluster", "postgres", "17.5",
                "admin", "password", "dbname", false, null, null, null, false));
        rdsService.deleteDbCluster("reused-cluster");
        assertNotNull(rdsService.createDbCluster("reused-cluster", "postgres", "17.5",
                "admin", "password", "dbname", false, null, null, null, false));
        verify(containerManager, times(2)).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** The instance-side twin: a successful create must release the identifier too. */
    @Test
    void deleteThenRecreateReusesTheInstanceIdentifier() {
        assertNotNull(rdsService.createDbInstance("reused-db", "postgres", "17.5",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false));
        rdsService.deleteDbInstance("reused-db");
        assertNotNull(rdsService.createDbInstance("reused-db", "postgres", "17.5",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false));
        verify(containerManager, times(2)).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * The sentinel must be held until registration completes: releasing it
     * before the store write would re-open the double-provision window this
     * fix closes, for a duplicate landing between release and registration.
     */
    @Test
    void sentinelIsHeldUntilRegistrationCompletes() throws Exception {
        CountDownLatch insidePut = new CountDownLatch(1);
        CountDownLatch releasePut = new CountDownLatch(1);
        InMemoryStorage<String, DbCluster> blockingClusters = new InMemoryStorage<>() {
            @Override
            public void put(String key, DbCluster value) {
                insidePut.countDown();
                try {
                    assertTrue(releasePut.await(5, TimeUnit.SECONDS), "test released the latch");
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                super.put(key, value);
            }
        };
        RdsService service = newService(containerManager, proxyManager,
                new InMemoryStorage<>(), blockingClusters,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DbCluster> first = executor.submit(() -> service.createDbCluster(
                    "ordered-cluster", "postgres", "17.5", "admin", "password", "dbname",
                    false, null, null, null, false));
            assertTrue(insidePut.await(5, TimeUnit.SECONDS), "first create reached registration");

            AwsException fault = assertThrows(AwsException.class, () ->
                    service.createDbCluster("ordered-cluster", "postgres", "17.5",
                            "admin", "password", "dbname", false, null, null, null, false));
            assertEquals("DBClusterAlreadyExistsFault", fault.getErrorCode());

            releasePut.countDown();
            assertNotNull(first.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    /** A failed create must release the identifier so a clean retry can proceed. */
    @Test
    void createDbClusterFailureReleasesTheIdentifierForRetry() {
        when(containerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("image pull failed"))
                .thenReturn(new RdsContainerHandle("cont-id", "id", "localhost", 5432));

        assertThrows(RuntimeException.class, () ->
                rdsService.createDbCluster("retry-cluster", "postgres", "17.5",
                        "admin", "password", "dbname", false, null, null, null, false));

        assertNotNull(rdsService.createDbCluster("retry-cluster", "postgres", "17.5",
                "admin", "password", "dbname", false, null, null, null, false));
        verify(containerManager, times(2)).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createDbInstanceStartsContainerWithRequestedEngineVersionImage() {
        rdsService.createDbInstance("mydb", "postgres", "18.1",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        verify(containerManager).start(
                eq("arn:aws:rds:us-east-1:123456789012:db:mydb"), eq("mydb"),
                any(), any(), eq(DatabaseEngine.POSTGRES),
                eq("postgres:18.1-alpine"), eq("admin"), eq("password"), eq("dbname"));
    }

    @Test
    void configuredPostgresImageIsNotRewrittenForRequestedEngineVersion() {
        when(rdsConfig.defaultPostgresImage()).thenReturn(Optional.of("postgres:16.14-alpine3.23"));

        rdsService.createDbCluster("cluster1", "postgres", "16.3",
                "admin", "password", "dbname", false, null);

        verify(containerManager).start(
                eq("arn:aws:rds:us-east-1:123456789012:cluster:cluster1"), eq("cluster1"),
                any(), any(), eq(DatabaseEngine.POSTGRES),
                eq("postgres:16.14-alpine3.23"), eq("admin"), eq("password"), eq("dbname"));
    }

    @Test
    void explicitlyConfiguredDefaultPostgresImageIsNotRewritten() {
        when(rdsConfig.defaultPostgresImage())
                .thenReturn(Optional.of(EmulatorConfig.RdsServiceConfig.DEFAULT_POSTGRES_IMAGE));

        rdsService.createDbCluster("cluster1", "postgres", "18.1",
                "admin", "password", "dbname", false, null);

        verify(containerManager).start(
                eq("arn:aws:rds:us-east-1:123456789012:cluster:cluster1"), eq("cluster1"),
                any(), any(), eq(DatabaseEngine.POSTGRES),
                eq(EmulatorConfig.RdsServiceConfig.DEFAULT_POSTGRES_IMAGE),
                eq("admin"), eq("password"), eq("dbname"));
    }

    @Test
    void dbInstanceTagsRoundTripAndMutateByArn() {
        DbInstance instance = rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, false, null,
                java.util.Map.of("example:ClusterId", "cluster-a"));

        assertEquals(java.util.Map.of("example:ClusterId", "cluster-a"),
                rdsService.listTagsForResource(instance.getDbInstanceArn()));

        rdsService.addTagsToResource(instance.getDbInstanceArn(), java.util.Map.of("Name", "mydb"));
        assertEquals(java.util.Map.of("example:ClusterId", "cluster-a", "Name", "mydb"),
                rdsService.listTagsForResource(instance.getDbInstanceArn()));

        rdsService.removeTagsFromResource(instance.getDbInstanceArn(), java.util.List.of("Name"));
        assertEquals(java.util.Map.of("example:ClusterId", "cluster-a"),
                rdsService.listTagsForResource(instance.getDbInstanceArn()));
    }

    @Test
    void dbInstanceEndpointUsesResolvedProxyHost() {
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.resolve()).thenReturn("floci.local");
        RdsService service = new RdsService(containerManager, proxyManager, ec2Service, regionResolver, config,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), null, dockerHostResolver, null);

        DbInstance instance = service.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        assertEquals("floci.local", instance.getEndpoint().address());
    }

    @Test
    void dbInstanceEndpointUsesPublishedProxyPort() {
        CurrentContainerNetworkResolver currentContainerNetworkResolver = mock(CurrentContainerNetworkResolver.class);
        when(config.services().rds().endpointHost()).thenReturn(Optional.of("localhost"));
        when(currentContainerNetworkResolver.resolvePublishedPort(7000)).thenReturn(OptionalInt.of(49173));
        RdsService service = new RdsService(containerManager, proxyManager, ec2Service, regionResolver, config,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), null, null, currentContainerNetworkResolver);

        DbInstance instance = service.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        assertEquals("localhost", instance.getEndpoint().address());
        assertEquals(49173, instance.getEndpoint().port());
        assertEquals(7000, instance.getProxyPort());
    }

    @Test
    void createDbInstanceWithManagedMasterPasswordCreatesSecret() {
        SecretsManagerService secretsManager = mock(SecretsManagerService.class);
        Secret secret = new Secret();
        secret.setArn("arn:aws:secretsmanager:us-east-1:123456789012:secret:rds!db-secret");
        when(secretsManager.createSecret(any(), any(), eq(null), any(), eq("kms-key-1"), any(), eq("rds"), eq("us-east-1")))
                .thenReturn(secret);
        RdsService service = newService(containerManager, proxyManager,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                secretsManager);

        DbInstance instance = service.createDbInstance("mydb", "postgres", "13",
                "admin", null, "dbname", "db.t3.micro",
                20, true, null, null, null, true, "kms-key-1");

        assertEquals("arn:aws:secretsmanager:us-east-1:123456789012:secret:rds!db-secret", instance.getMasterUserSecretArn());
        assertEquals("active", instance.getMasterUserSecretStatus());
        assertEquals("kms-key-1", instance.getMasterUserSecretKmsKeyId());
        assertNotNull(instance.getMasterPassword());
        assertTrue(instance.getMasterPassword().startsWith("floci-"));

        ArgumentCaptor<String> secretName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretString = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Secret.Tag>> secretTags = ArgumentCaptor.forClass(List.class);
        verify(secretsManager).createSecret(secretName.capture(), secretString.capture(), eq(null), any(),
                eq("kms-key-1"), secretTags.capture(), eq("rds"), eq("us-east-1"));
        assertTrue(secretName.getValue().startsWith("rds!db-"));
        assertTrue(secretString.getValue().contains("\"username\":\"admin\""));
        assertTrue(secretString.getValue().contains("\"password\":\"" + instance.getMasterPassword() + "\""));
        assertTrue(secretString.getValue().contains("\"dbInstanceIdentifier\":\"mydb\""));

        // AWS marks the secret it manages with both of these tags, alongside OwningService.
        assertTrue(secretTags.getValue().contains(
                new Secret.Tag("aws:secretsmanager:owningService", "rds")));
        assertTrue(secretTags.getValue().contains(
                new Secret.Tag("aws:rds:primaryDBInstanceArn", instance.getDbInstanceArn())));
    }

    @Test
    void backfillMarksMasterUserSecretsOfPersistedInstances() {
        SecretsManagerService secretsManager = mock(SecretsManagerService.class);
        Secret secret = new Secret();
        String secretArn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:rds!db-secret";
        secret.setArn(secretArn);
        when(secretsManager.createSecret(any(), any(), eq(null), any(), eq(null), any(), eq("rds"), eq("us-east-1")))
                .thenReturn(secret);
        RdsService service = newService(containerManager, proxyManager,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                secretsManager);

        service.createDbInstance("mydb", "postgres", "13",
                "admin", null, "dbname", "db.t3.micro",
                20, true, null, null, null, true, null);

        // An instance persisted by a floci that predates ownership tracking still names its
        // secret, which is what makes that secret managed — the name never is.
        service.backfillManagedSecretOwnership();

        verify(secretsManager).markOwnedByService(secretArn, "rds");
    }

    @Test
    void restorePersistedRuntimeRunsTheOwnershipBackfill() {
        // The lifecycle calls restorePersistedRuntime() after storageFactory.loadAll(), so the
        // backfill hangs off that rather than off its own StartupEvent observer, which would have
        // no ordering against the reload.
        SecretsManagerService secretsManager = mock(SecretsManagerService.class);
        Secret restored = new Secret();
        String restoredArn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:rds!db-secret";
        restored.setArn(restoredArn);
        when(secretsManager.createSecret(any(), any(), eq(null), any(), eq(null), any(), eq("rds"), eq("us-east-1")))
                .thenReturn(restored);
        RdsService service = newService(containerManager, proxyManager,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                secretsManager);

        service.createDbInstance("mydb", "postgres", "13",
                "admin", null, "dbname", "db.t3.micro",
                20, true, null, null, null, true, null);

        service.restorePersistedRuntime();

        verify(secretsManager).markOwnedByService(restoredArn, "rds");
    }

    @Test
    void backfillDoesNotStopStartupWhenASecretCannotBeMarked() {
        SecretsManagerService secretsManager = mock(SecretsManagerService.class);
        Secret secret = new Secret();
        secret.setArn("arn:aws:secretsmanager:us-east-1:123456789012:secret:rds!db-secret");
        when(secretsManager.createSecret(any(), any(), eq(null), any(), eq(null), any(), eq("rds"), eq("us-east-1")))
                .thenReturn(secret);
        doThrow(new IllegalStateException("storage unavailable"))
                .when(secretsManager).markOwnedByService(any(), any());
        RdsService service = newService(containerManager, proxyManager,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                secretsManager);

        service.createDbInstance("mydb", "postgres", "13",
                "admin", null, "dbname", "db.t3.micro",
                20, true, null, null, null, true, null);

        assertDoesNotThrow(() -> service.backfillManagedSecretOwnership());
    }

    @Test
    void backfillIgnoresInstancesWithoutAManagedSecret() {
        SecretsManagerService secretsManager = mock(SecretsManagerService.class);
        RdsService service = newService(containerManager, proxyManager,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                secretsManager);

        service.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        service.backfillManagedSecretOwnership();

        verify(secretsManager, never()).markOwnedByService(any(), any());
    }

    @Test
    void createDbInstanceRejectsUnknownParameterGroup() {
        AwsException exception = assertThrows(AwsException.class, () -> rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, "does-not-exist", null, null));

        assertEquals("DBParameterGroupNotFound", exception.getErrorCode());
        assertEquals("DBParameterGroupName doesn't refer to an existing DB parameter group.", exception.getMessage());
    }

    @Test
    void createDbInstanceRejectsIncompatibleParameterGroupFamily() {
        rdsService.createDbParameterGroup("pg1", "mysql8.0", "test group");

        AwsException exception = assertThrows(AwsException.class, () -> rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, "pg1", null, null));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
        assertEquals("Parameters that must not be used together were used together. Remove one of the conflicting parameters and try again.",
                exception.getMessage());
    }

    @Test
    void listDbInstancesIsCaseInsensitive() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);

        Collection<DbInstance> result = rdsService.listDbInstances("MYDB");
        assertEquals(1, result.size());
        assertEquals("mydb", result.iterator().next().getDbInstanceIdentifier());

        result = rdsService.listDbInstances("mydb");
        assertEquals(1, result.size());
    }

    @Test
    void listDbInstancesReturnsEmptyWhenNotFound() {
        Collection<DbInstance> result = rdsService.listDbInstances("nonexistent");
        assertTrue(result.isEmpty());
    }

    @Test
    void listDbInstancesMatchesByArn() {
        DbInstance created = rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);

        Collection<DbInstance> result = rdsService.listDbInstances(created.getDbInstanceArn());
        assertEquals(1, result.size());
        assertEquals("mydb", result.iterator().next().getDbInstanceIdentifier());
    }

    @Test
    void listDbClustersMatchesByArn() {
        DbCluster created = rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null);

        Collection<DbCluster> result = rdsService.listDbClusters(created.getDbClusterArn());
        assertEquals(1, result.size());
        assertEquals("cluster1", result.iterator().next().getDbClusterIdentifier());
    }

    @Test
    void listDbInstancesDoesNotMatchForeignArn() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);

        assertTrue(rdsService.listDbInstances(
                "arn:aws:rds:us-east-1:999999999999:db:mydb").isEmpty(), "cross-account ARN must not match");
        assertTrue(rdsService.listDbInstances(
                "arn:aws:rds:eu-west-1:123456789012:db:mydb").isEmpty(), "cross-region ARN must not match");
    }

    @Test
    void listDbClustersDoesNotMatchForeignArn() {
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null);

        assertTrue(rdsService.listDbClusters(
                "arn:aws:rds:us-east-1:999999999999:cluster:cluster1").isEmpty(), "cross-account ARN must not match");
        assertTrue(rdsService.listDbClusters(
                "arn:aws:rds:eu-west-1:123456789012:cluster:cluster1").isEmpty(), "cross-region ARN must not match");
    }

    @Test
    void listDbInstancesByDbiResourceIdsUsesExactOrMatching() {
        DbInstance instance = rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);

        Collection<DbInstance> result = rdsService.listDbInstancesByDbiResourceIds(
                List.of("db-missing", instance.getDbiResourceId()));

        assertEquals(1, result.size());
        assertEquals("mydb", result.iterator().next().getDbInstanceIdentifier());
        assertTrue(rdsService.listDbInstancesByDbiResourceIds(
                List.of(instance.getDbiResourceId().toLowerCase())).isEmpty());
    }

    @Test
    void modifyDbInstanceBlankPasswordDoesNotOverwriteExistingPassword() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "original-password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);

        DbInstance modified = rdsService.modifyDbInstance("mydb", "   ", null, null);

        assertEquals("original-password", modified.getMasterPassword());
        assertFalse(modified.isIamDatabaseAuthenticationEnabled());
    }

    @Test
    void modifyDbInstanceCanToggleIamWithoutChangingPassword() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "original-password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);

        DbInstance modified = rdsService.modifyDbInstance("mydb", null, true, null);

        assertEquals("original-password", modified.getMasterPassword());
        assertTrue(modified.isIamDatabaseAuthenticationEnabled());
    }

    @Test
    void modifyDbInstanceAppliesAutoMinorVersionUpgrade() {
        // #2420 review: ModifyDBInstance silently dropped this - created true (the AWS
        // default), an explicit modify to false must actually take effect and stick on a
        // later read, not just get echoed back unchanged.
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "original-password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);

        DbInstance modified = rdsService.modifyDbInstance(
                "mydb", null, null, null, List.of(), null, null, false);
        assertFalse(modified.isAutoMinorVersionUpgrade());

        DbInstance described = rdsService.getDbInstance("mydb");
        assertFalse(described.isAutoMinorVersionUpgrade());
    }

    @Test
    void modifyDbInstanceRejectsMissingDbSubnetGroup() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "original-password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.modifyDbInstance("mydb", null, null, "missing-subnet-group"));

        assertEquals("DBSubnetGroupNotFoundFault", exception.getErrorCode());
    }

    @Test
    void dbSubnetGroupRoundTrip() {
        DbSubnetGroup group = rdsService.createDbSubnetGroup(
                "sample-db-subnets", "test", java.util.List.of("subnet-default-a", "subnet-default-b"));

        assertEquals("sample-db-subnets", group.getDbSubnetGroupName());
        assertEquals(java.util.List.of("subnet-default-a", "subnet-default-b"), group.getSubnetIds());
        assertEquals(1, rdsService.listDbSubnetGroups("sample-db-subnets").size());

        rdsService.deleteDbSubnetGroup("sample-db-subnets");
        AwsException missing = assertThrows(AwsException.class,
                () -> rdsService.listDbSubnetGroups("sample-db-subnets"));
        assertEquals("DBSubnetGroupNotFoundFault", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void listDbSubnetGroupsFaultsForMissingName() {
        AwsException missing = assertThrows(AwsException.class,
                () -> rdsService.listDbSubnetGroups("does-not-exist"));
        assertEquals("DBSubnetGroupNotFoundFault", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void listDbSubnetGroupsStillResolvesSyntheticDefault() {
        Collection<DbSubnetGroup> groups = rdsService.listDbSubnetGroups("default");
        assertEquals(1, groups.size());
        assertEquals("default", groups.iterator().next().getDbSubnetGroupName());
    }

    @Test
    void dbSubnetGroupTagsRoundTripAndMutateByArn() {
        rdsService.createDbSubnetGroup(
                "sample-db-subnets", "test", java.util.List.of("subnet-default-a", "subnet-default-b"));
        String arn = "arn:aws:rds:us-east-1:123456789012:subgrp:sample-db-subnets";

        // A subnet group with no tags must list cleanly — previously this threw DBInstanceNotFound (404)
        // because every ResourceName was resolved as a DB instance.
        assertEquals(java.util.Map.of(), rdsService.listTagsForResource(arn));

        rdsService.addTagsToResource(arn, java.util.Map.of("Name", "sample-db-subnets"));
        assertEquals(java.util.Map.of("Name", "sample-db-subnets"),
                rdsService.listTagsForResource(arn));

        rdsService.removeTagsFromResource(arn, java.util.List.of("Name"));
        assertEquals(java.util.Map.of(), rdsService.listTagsForResource(arn));
    }

    @Test
    void dbSubnetGroupTagsSurviveModify() {
        rdsService.createDbSubnetGroup(
                "sample-db-subnets", "test", java.util.List.of("subnet-default-a", "subnet-default-b"));
        String arn = "arn:aws:rds:us-east-1:123456789012:subgrp:sample-db-subnets";
        rdsService.addTagsToResource(arn, java.util.Map.of("Name", "sample-db-subnets"));

        rdsService.modifyDbSubnetGroup("sample-db-subnets", java.util.List.of("subnet-default-a"));

        assertEquals(java.util.Map.of("Name", "sample-db-subnets"),
                rdsService.listTagsForResource(arn));
    }

    @Test
    void sameNamedSubnetGroupsAreRegionScopedIncludingTagsAndDeletion() {
        List<String> westSubnetIds = List.of("subnet-west-a", "subnet-west-b");
        when(ec2Service.describeSubnets(eq("us-west-2"), eq(westSubnetIds), eq(Map.of())))
                .thenReturn(List.of(
                        subnet("subnet-west-a", "vpc-west", "us-west-2a"),
                        subnet("subnet-west-b", "vpc-west", "us-west-2b")));

        DbSubnetGroup east = rdsService.createDbSubnetGroup(
                "regional-subnets", "east", PROXY_SUBNET_IDS, "us-east-1");
        DbSubnetGroup west = rdsService.createDbSubnetGroup(
                "regional-subnets", "west", westSubnetIds, "us-west-2");

        rdsService.addTagsToResource(
                east.getDbSubnetGroupArn(), Map.of("region", "east"), "us-east-1");
        rdsService.addTagsToResource(
                west.getDbSubnetGroupArn(), Map.of("region", "west"), "us-west-2");

        assertEquals("vpc-default", rdsService.getDbSubnetGroup(
                "regional-subnets", "us-east-1").getVpcId());
        assertEquals("vpc-west", rdsService.getDbSubnetGroup(
                "regional-subnets", "us-west-2").getVpcId());
        assertEquals(Map.of("region", "east"), rdsService.listTagsForResource(
                east.getDbSubnetGroupArn(), "us-east-1"));
        assertEquals(Map.of("region", "west"), rdsService.listTagsForResource(
                west.getDbSubnetGroupArn(), "us-west-2"));

        rdsService.deleteDbSubnetGroup("regional-subnets", "us-west-2");

        assertEquals(east.getDbSubnetGroupArn(), rdsService.getDbSubnetGroup(
                "regional-subnets", "us-east-1").getDbSubnetGroupArn());
        AwsException missingWest = assertThrows(AwsException.class, () ->
                rdsService.getDbSubnetGroup("regional-subnets", "us-west-2"));
        assertEquals("DBSubnetGroupNotFoundFault", missingWest.getErrorCode());
    }

    @Test
    void listTagsForMissingSubnetGroupReturnsSubnetGroupNotFound() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.listTagsForResource("arn:aws:rds:us-east-1:123456789012:subgrp:missing"));

        assertEquals("DBSubnetGroupNotFoundFault", exception.getErrorCode());
    }

    @Test
    void dbClusterTagsRoundTripByArn() {
        DbCluster cluster = rdsService.createDbCluster("cluster1", "postgres", "13",
                "admin", "password", "dbname", false, null);

        assertEquals(java.util.Map.of(), rdsService.listTagsForResource(cluster.getDbClusterArn()));

        rdsService.addTagsToResource(cluster.getDbClusterArn(), java.util.Map.of("env", "test"));
        assertEquals(java.util.Map.of("env", "test"),
                rdsService.listTagsForResource(cluster.getDbClusterArn()));
    }

    @Test
    void tagOperationsRejectUnsupportedResourceArn() {
        // Parameter groups are tagged now, so an absent one is a missing resource rather than a
        // missing feature. A type Floci still does not model keeps the limitation message.
        AwsException absentGroup = assertThrows(AwsException.class, () ->
                rdsService.listTagsForResource("arn:aws:rds:us-east-1:123456789012:pg:some-parameter-group"));
        assertEquals("DBParameterGroupNotFound", absentGroup.getErrorCode());

        AwsException unsupportedType = assertThrows(AwsException.class, () ->
                rdsService.listTagsForResource("arn:aws:rds:us-east-1:123456789012:snapshot:some-snapshot"));
        assertEquals("InvalidParameterValue", unsupportedType.getErrorCode());
        // The type is valid on real AWS; the message must present this as a Floci limitation.
        assertTrue(unsupportedType.getMessage().contains("not yet implemented by Floci"));
    }

    @Test
    void tagOperationsRejectTypelessRdsArn() {
        // Real AWS rejects an RDS ARN whose resource part is not <type>:<id> with InvalidParameterValue;
        // previously this fell back to a DB-instance lookup and returned DBInstanceNotFound.
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.listTagsForResource("arn:aws:rds:us-east-1:123456789012:mydb"));

        assertEquals("InvalidParameterValue", exception.getErrorCode());
    }

    @Test
    void tagOperationsRejectNonRdsArn() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.listTagsForResource("arn:aws:s3:::some-bucket"));

        assertEquals("InvalidParameterValue", exception.getErrorCode());
    }

    @Test
    void tagOperationsRejectMalformedArn() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.listTagsForResource("arn:aws:rds:incomplete"));

        assertEquals("InvalidParameterValue", exception.getErrorCode());
    }

    @Test
    void createDbInstanceRejectsMissingDbSubnetGroupBeforeStartingRuntime() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbInstance("mydb", "postgres", "13",
                        "admin", "password", "dbname", "db.t3.micro",
                        20, false, null, "missing-subnet-group", null));

        assertEquals("DBSubnetGroupNotFoundFault", exception.getErrorCode());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(proxyManager, never()).startProxy(any(), any(), anyBoolean(), anyInt(),
                any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void describeOrderableDbInstanceOptionsFiltersByEngineVersionAndClass() {
        var result = rdsService.describeOrderableDbInstanceOptions(
                "postgres", "18.1", "db.t3.micro");

        assertEquals(1, result.size());
        assertEquals("postgres", result.getFirst().get("engine"));
        assertEquals("18.1", result.getFirst().get("engineVersion"));
        assertEquals("db.t3.micro", result.getFirst().get("dbInstanceClass"));
    }

    @Test
    void describeOrderableDbInstanceOptionsIncludesModernGravitonPostgresClasses() {
        var flociPinned = rdsService.describeOrderableDbInstanceOptions(
                "postgres", "18.1", "db.m8g.large");
        var awsEquivalent = rdsService.describeOrderableDbInstanceOptions(
                "postgres", "18.4", "db.m8g.large");

        assertEquals(1, flociPinned.size());
        assertEquals("db.m8g.large", flociPinned.getFirst().get("dbInstanceClass"));
        assertEquals("18.1", flociPinned.getFirst().get("engineVersion"));
        assertEquals(1, awsEquivalent.size());
        assertEquals("db.m8g.large", awsEquivalent.getFirst().get("dbInstanceClass"));
        assertEquals("18.4", awsEquivalent.getFirst().get("engineVersion"));
    }

    @Test
    void describeOrderableDbInstanceOptionsIncludesCurrentSmallGravitonPostgresClass() {
        var result = rdsService.describeOrderableDbInstanceOptions(
                "postgres", "16.14", "db.t4g.small");

        assertEquals(1, result.size());
        assertEquals("db.t4g.small", result.getFirst().get("dbInstanceClass"));
        assertEquals("16.14", result.getFirst().get("engineVersion"));
    }

    @Test
    void deleteDbClusterFailsWhenMembersRemain() {
        DbCluster cluster = rdsService.createDbCluster("cluster1", "postgres", "13",
                "admin", "password", "dbname", false, null, null, null, false);
        cluster.getDbClusterMembers().add("instance-1");

        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.deleteDbCluster("cluster1"));

        assertEquals("InvalidDBClusterStateFault", exception.getErrorCode());
        assertTrue(exception.getMessage().contains("still has DB instances"));
    }

    @Test
    void mockModeCreatesClusterAvailableWithoutContainerOrProxy() {
        when(config.services().rds().mock()).thenReturn(true);

        DbCluster cluster = rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null);

        assertEquals(DbInstanceStatus.AVAILABLE, cluster.getStatus());
        assertEquals("localhost", cluster.getEndpoint().address());
        assertTrue(cluster.getEndpoint().port() > 0);
        assertNull(cluster.getContainerId());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(proxyManager, never()).startProxy(any(), any(), anyBoolean(), anyInt(), any(), anyInt(),
                any(), any(), any(), any(), any());
    }

    @Test
    void createDbClusterAppliesServerlessV2Scaling() {
        when(config.services().rds().mock()).thenReturn(true);

        DbCluster cluster = rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null, null, null, false, "us-east-1",
                0.0, 16.0, 600);

        assertEquals(0.0, cluster.getServerlessV2MinCapacity());
        assertEquals(16.0, cluster.getServerlessV2MaxCapacity());
        assertEquals(600, cluster.getServerlessV2SecondsUntilAutoPause());
    }

    @Test
    void createDbClusterRejectsServerlessV2ScalingForNonAuroraEngines() {
        when(config.services().rds().mock()).thenReturn(true);

        for (String engine : List.of("postgres", "mysql", "mariadb")) {
            String clusterId = "cluster-" + engine;
            AwsException exception = assertThrows(AwsException.class,
                    () -> rdsService.createDbCluster(
                            clusterId, engine, null, "admin", "password", "dbname",
                            false, null, null, null, false, "us-east-1",
                            0.5, 16.0, null));

            assertEquals("InvalidParameterCombination", exception.getErrorCode());
            assertEquals(400, exception.getHttpStatus());
            assertEquals(
                    "Parameters that must not be used together were used together. "
                            + "Remove one of the conflicting parameters and try again.",
                    exception.getMessage());
            assertTrue(rdsService.listDbClusters(clusterId).isEmpty());
        }
    }

    @Test
    void createDbClusterDefaultsAndClearsServerlessV2AutoPauseInterval() {
        when(config.services().rds().mock()).thenReturn(true);

        DbCluster autoPauseCluster = rdsService.createDbCluster(
                "auto-pause", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null, null, null, false, "us-east-1",
                0.0, 16.0);
        DbCluster alwaysActiveCluster = rdsService.createDbCluster(
                "always-active", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null, null, null, false, "us-east-1",
                0.5, 16.0, 600);

        assertEquals(300, autoPauseCluster.getServerlessV2SecondsUntilAutoPause());
        assertNull(alwaysActiveCluster.getServerlessV2SecondsUntilAutoPause());
    }

    @Test
    void modifyDbClusterAppliesServerlessV2ScalingConfiguration() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null, null, null, false, "us-east-1",
                0.0, 16.0, 600);

        DbCluster widerRange = rdsService.modifyDbCluster(
                "cluster1", null, null, null, 32.0, null);

        assertEquals(0.0, widerRange.getServerlessV2MinCapacity());
        assertEquals(32.0, widerRange.getServerlessV2MaxCapacity());
        assertEquals(600, widerRange.getServerlessV2SecondsUntilAutoPause());

        DbCluster longerAutoPause = rdsService.modifyDbCluster(
                "cluster1", null, null, null, null, 900);

        assertEquals(0.0, longerAutoPause.getServerlessV2MinCapacity());
        assertEquals(32.0, longerAutoPause.getServerlessV2MaxCapacity());
        assertEquals(900, longerAutoPause.getServerlessV2SecondsUntilAutoPause());

        DbCluster alwaysActiveCluster = rdsService.modifyDbCluster(
                "cluster1", null, null, 0.5, null, null);
        assertEquals(0.5, alwaysActiveCluster.getServerlessV2MinCapacity());
        assertEquals(32.0, alwaysActiveCluster.getServerlessV2MaxCapacity());
        assertNull(alwaysActiveCluster.getServerlessV2SecondsUntilAutoPause());

        DbCluster autoPauseAgain = rdsService.modifyDbCluster(
                "cluster1", null, null, 0.0, null, null);
        assertEquals(300, autoPauseAgain.getServerlessV2SecondsUntilAutoPause());

        DbCluster passwordOnlyChange = rdsService.modifyDbCluster(
                "cluster1", "new-password", null);
        assertEquals(0.0, passwordOnlyChange.getServerlessV2MinCapacity());
        assertEquals(32.0, passwordOnlyChange.getServerlessV2MaxCapacity());
        assertEquals(300, passwordOnlyChange.getServerlessV2SecondsUntilAutoPause());
    }

    @Test
    void modifyDbClusterRejectsServerlessV2ScalingForNonAuroraCluster() {
        when(config.services().rds().mock()).thenReturn(true);
        DbCluster cluster = rdsService.createDbCluster(
                "cluster1", "postgres", "16.3", "admin", "original-password",
                "dbname", false, null);

        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.modifyDbCluster(
                        "cluster1", "new-password", true, 0.5, 16.0, null));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
        assertEquals(
                "Parameters that must not be used together were used together. "
                        + "Remove one of the conflicting parameters and try again.",
                exception.getMessage());
        assertEquals("original-password", cluster.getMasterPassword());
        assertFalse(cluster.isIamDatabaseAuthenticationEnabled());
        assertNull(cluster.getServerlessV2MinCapacity());
        assertNull(cluster.getServerlessV2MaxCapacity());
    }

    @Test
    void modifyDbClusterCanAddServerlessV2ScalingToExistingAuroraCluster() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null);

        DbCluster cluster = rdsService.modifyDbCluster(
                "cluster1", null, null, 0.5, 16.0, null);

        assertEquals("aurora-postgresql", cluster.getEngineIdentifier());
        assertEquals(0.5, cluster.getServerlessV2MinCapacity());
        assertEquals(16.0, cluster.getServerlessV2MaxCapacity());
    }

    @Test
    void modifyDbClusterRejectsScalingWhenPersistedEngineIdentityIsMissing() {
        when(config.services().rds().mock()).thenReturn(true);
        DbCluster cluster = rdsService.createDbCluster(
                "cluster1", "aurora-postgresql", "16.3", "admin", "password",
                "dbname", false, null);
        cluster.setEngineIdentifier(null);

        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.modifyDbCluster(
                        "cluster1", null, null, 0.5, 16.0, null));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
        assertNull(cluster.getServerlessV2MinCapacity());
        assertNull(cluster.getServerlessV2MaxCapacity());
    }

    @Test
    void modifyDbClusterRejectsPartialScalingWithoutAnExistingConfiguration() {
        when(config.services().rds().mock()).thenReturn(true);
        DbCluster cluster = rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "original-password", "dbname", false, null);

        assertThrows(AwsException.class, () -> rdsService.modifyDbCluster(
                "cluster1", "new-password", true, null, 16.0, null));

        assertEquals("original-password", cluster.getMasterPassword());
        assertFalse(cluster.isIamDatabaseAuthenticationEnabled());
        assertNull(cluster.getServerlessV2MinCapacity());
        assertNull(cluster.getServerlessV2MaxCapacity());
    }

    @Test
    void modifyDbClusterValidatesScalingBeforeApplyingOtherChanges() {
        when(config.services().rds().mock()).thenReturn(true);
        DbCluster cluster = rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "original-password", "dbname", false, null);

        assertThrows(AwsException.class, () -> rdsService.modifyDbCluster(
                "cluster1", "new-password", true, 0.0, 16.0, 299));

        assertEquals("original-password", cluster.getMasterPassword());
        assertFalse(cluster.isIamDatabaseAuthenticationEnabled());
        assertNull(cluster.getServerlessV2MinCapacity());
        assertNull(cluster.getServerlessV2MaxCapacity());
    }

    @Test
    void serverlessV2CapacityValidationEnforcesAcuConstraints() {
        // Non-half-step increment.
        assertThrows(AwsException.class, () -> rdsService.validateServerlessV2Capacity(0.3, 16.0));
        // Above the 256-ACU ceiling.
        assertThrows(AwsException.class, () -> rdsService.validateServerlessV2Capacity(0.5, 300.0));
        // AWS requires MaxCapacity to be greater than 0.5 ACUs.
        assertThrows(AwsException.class, () -> rdsService.validateServerlessV2Capacity(0.0, 0.5));
        // Non-finite values are not valid AWS Query numbers.
        assertThrows(AwsException.class, () -> rdsService.validateServerlessV2Capacity(Double.NaN, 16.0));
        assertThrows(AwsException.class, () -> rdsService.validateServerlessV2Capacity(0.5, Double.NaN));
        assertThrows(AwsException.class,
                () -> rdsService.validateServerlessV2Capacity(0.5, Double.POSITIVE_INFINITY));
        assertThrows(AwsException.class,
                () -> rdsService.validateServerlessV2Capacity(Double.NEGATIVE_INFINITY, 16.0));
        // MaxCapacity below MinCapacity.
        assertThrows(AwsException.class, () -> rdsService.validateServerlessV2Capacity(16.0, 8.0));
        // Valid: 0 (auto-pause), the 256 ceiling, and half-step values.
        assertDoesNotThrow(() -> rdsService.validateServerlessV2Capacity(0.0, 256.0));
        assertDoesNotThrow(() -> rdsService.validateServerlessV2Capacity(0.5, 128.0));
        // Both null is a no-op (not a Serverless v2 cluster).
        assertDoesNotThrow(() -> rdsService.validateServerlessV2Capacity(null, null));
        // A create-time or otherwise incomplete effective configuration requires both bounds.
        assertThrows(AwsException.class, () -> rdsService.validateServerlessV2Capacity(0.5, null));
        assertThrows(AwsException.class, () -> rdsService.validateServerlessV2Capacity(null, 16.0));
        assertThrows(AwsException.class,
                () -> rdsService.validateServerlessV2ScalingConfiguration(0.0, 16.0, 299));
        assertThrows(AwsException.class,
                () -> rdsService.validateServerlessV2ScalingConfiguration(0.0, 16.0, 86_401));
        assertThrows(AwsException.class,
                () -> rdsService.validateServerlessV2ScalingConfiguration(null, null, 300));
        assertEquals(300,
                rdsService.validateServerlessV2ScalingConfiguration(0.0, 16.0, null));
        assertEquals(86_400,
                rdsService.validateServerlessV2ScalingConfiguration(0.0, 16.0, 86_400));
        assertNull(rdsService.validateServerlessV2ScalingConfiguration(0.5, 16.0, 600));
    }

    @Test
    void mockModeCreatesClusterInstanceAvailableWithoutContainer() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null);

        DbInstance instance = rdsService.createDbInstance("inst1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", "db.serverless",
                0, false, null, null, "cluster1");

        assertEquals(DbInstanceStatus.AVAILABLE, instance.getStatus());
        assertEquals("localhost", instance.getEndpoint().address());
        // No Docker volume name may be persisted: the mock cluster has a null volume id, so the
        // fallback would fabricate a name that a later non-mock restore could try to reference.
        assertNull(instance.getDockerVolumeName());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(proxyManager, never()).startProxy(any(), any(), anyBoolean(), anyInt(), any(), anyInt(),
                any(), any(), any(), any(), any());
    }

    @Test
    void mockModeCreatesStandaloneInstanceAvailableWithoutContainer() {
        when(config.services().rds().mock()).thenReturn(true);

        DbInstance instance = rdsService.createDbInstance("standalone", "postgres", "16",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        assertEquals(DbInstanceStatus.AVAILABLE, instance.getStatus());
        assertNull(instance.getContainerId());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void mockModeDeleteClusterSkipsDockerCleanup() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null);

        rdsService.deleteDbCluster("cluster1");

        verify(containerManager, never()).stop(any());
        verify(containerManager, never()).removeVolume(any(), any(), any());
    }

    @Test
    void mockModeDeleteStandaloneInstanceSkipsDockerCleanup() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbInstance("standalone", "postgres", "16",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        rdsService.deleteDbInstance("standalone");

        verify(containerManager, never()).stop(any());
        verify(containerManager, never()).removeVolume(any(), any(), any());
    }

    @Test
    void mockModeAssignsDistinctEndpointPorts() {
        when(config.services().rds().mock()).thenReturn(true);

        DbCluster a = rdsService.createDbCluster("cluster-a", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null);
        DbCluster b = rdsService.createDbCluster("cluster-b", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null);

        assertNotEquals(a.getEndpoint().port(), b.getEndpoint().port());
    }

    @Test
    void mockModeRebootSkipsContainerAndProxy() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbInstance("standalone", "postgres", "16",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        DbInstance rebooted = rdsService.rebootDbInstance("standalone");

        assertEquals(DbInstanceStatus.AVAILABLE, rebooted.getStatus());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(containerManager, never()).stop(any());
        verify(proxyManager, never()).startProxy(any(), any(), anyBoolean(), anyInt(), any(), anyInt(),
                any(), any(), any(), any(), any());
    }

    @Test
    void rebootStopsBeforeRestartAndPersistsFailedStatusWhenCleanupFails() {
        DbInstance instance = rdsService.createDbInstance(
                "standalone", "postgres", "16", "admin", "password", "dbname",
                "db.t3.micro", 20, false, null, null, null);
        doThrow(new IllegalStateException("Docker cleanup failed"))
                .when(containerManager).stop(any());

        assertThrows(IllegalStateException.class, () ->
                rdsService.rebootDbInstance("standalone"));

        DbInstance failed = rdsService.getDbInstance("standalone");
        assertEquals(DbInstanceStatus.FAILED, failed.getStatus());
        assertEquals(instance.getContainerId(), failed.getContainerId());
        verify(containerManager, times(1)).start(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(proxyManager).stopProxy("rds-resource:" + instance.getDbInstanceArn());
    }

    @Test
    void createDbClusterRejectsUnknownClusterParameterGroup() {
        AwsException exception = assertThrows(AwsException.class, () -> rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, "does-not-exist"));

        assertEquals("DBClusterParameterGroupNotFound", exception.getErrorCode());
        assertEquals("DBClusterParameterGroupName doesn't refer to an existing DB cluster parameter group.", exception.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
            "aurora-mysql, 5.7.mysql_aurora.2.12.4, aurora-mysql5.7",
            "aurora-mysql, 8.0.mysql_aurora.3.10.0, aurora-mysql8.0",
            "aurora-mysql, 8.4.mysql_aurora.8.4.7, aurora-mysql8.4",
            "aurora-postgresql, 11.21, aurora-postgresql11",
            "aurora-postgresql, 12.22, aurora-postgresql12",
            "aurora-postgresql, 13.18, aurora-postgresql13",
            "aurora-postgresql, 14.15, aurora-postgresql14",
            "aurora-postgresql, 15.10, aurora-postgresql15",
            "aurora-postgresql, 16.4, aurora-postgresql16",
            "aurora-postgresql, 17.4, aurora-postgresql17",
            "aurora-postgresql, 18.3, aurora-postgresql18",
            "mysql, 8.0.36, mysql8.0",
            "mysql, 8.4.7, mysql8.4",
            "postgres, 13.20, postgres13",
            "postgres, 14.17, postgres14",
            "postgres, 15.12, postgres15",
            "postgres, 16.8, postgres16",
            "postgres, 17.4, postgres17",
            "postgres, 18.1, postgres18"
    })
    void createDbClusterAcceptsCurrentAwsDefaultClusterParameterGroups(
            String engine, String engineVersion, String family) {
        String groupName = "default." + family;

        DbCluster cluster = rdsService.createDbCluster("cluster", engine, engineVersion,
                "admin", "password", "coredb", false, groupName);

        assertEquals("cluster", cluster.getDbClusterIdentifier());
        assertEquals(family, rdsService.getDbClusterParameterGroup(groupName).getDbParameterGroupFamily());
    }

    @Test
    void createDbClusterRejectsUnsupportedAwsDefaultClusterParameterGroup() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbCluster("aurora-cluster", "aurora-postgresql", "16.4",
                        "admin", "password", "coredb", false,
                        "default.aurora-postgresql999"));

        assertEquals("DBClusterParameterGroupNotFound", exception.getErrorCode());
    }

    @Test
    void listDbClusterParameterGroupsAlwaysIncludesStableManagedDefaults() {
        List<String> before = rdsService.listDbClusterParameterGroups(null).stream()
                .map(group -> group.getDbClusterParameterGroupName()
                        + ":" + group.getDbParameterGroupFamily())
                .toList();

        Collection<DbClusterParameterGroup> groups =
                rdsService.listDbClusterParameterGroups("default.aurora-postgresql16");

        assertEquals(1, groups.size());
        DbClusterParameterGroup group = groups.iterator().next();
        assertEquals("default.aurora-postgresql16", group.getDbClusterParameterGroupName());
        assertEquals("aurora-postgresql16", group.getDbParameterGroupFamily());
        assertEquals("Default cluster parameter group", group.getDescription());
        List<String> after = rdsService.listDbClusterParameterGroups(null).stream()
                .map(item -> item.getDbClusterParameterGroupName()
                        + ":" + item.getDbParameterGroupFamily())
                .toList();
        assertEquals(CURRENT_MANAGED_CLUSTER_PARAMETER_GROUP_FAMILIES.stream()
                .map(family -> "default." + family + ":" + family)
                .toList(), before);
        assertEquals(before, after);
    }

    @Test
    void unsupportedManagedDefaultNamesAreNotFabricatedByReads() {
        List<String> before = rdsService.listDbClusterParameterGroups(null).stream()
                .map(DbClusterParameterGroup::getDbClusterParameterGroupName)
                .toList();

        for (String name : List.of("default.", "default.not-a-real-family",
                "default.aurora-postgresql999", "default.mariadb11.2")) {
            AwsException exception = assertThrows(
                    AwsException.class, () -> rdsService.getDbClusterParameterGroup(name));
            assertEquals("DBClusterParameterGroupNotFound", exception.getErrorCode());
        }

        assertEquals(before, rdsService.listDbClusterParameterGroups(null).stream()
                .map(DbClusterParameterGroup::getDbClusterParameterGroupName)
                .toList());
    }

    @Test
    void namedClusterParameterGroupListingUsesAwsNotFoundCode() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.listDbClusterParameterGroups("default.not-a-real-family"));

        assertEquals("DBParameterGroupNotFound", exception.getErrorCode());
        assertEquals("DBParameterGroupName doesn't refer to an existing DB parameter group.",
                exception.getMessage());
        assertEquals(404, exception.getHttpStatus());
    }

    @Test
    void createDbClusterRejectsIncompatibleClusterParameterGroupFamily() {
        rdsService.createDbClusterParameterGroup("cpg1", "aurora-mysql8.0", "test group");

        AwsException exception = assertThrows(AwsException.class, () -> rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, "cpg1"));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
        assertEquals("Parameters that must not be used together were used together. Remove one of the conflicting parameters and try again.",
                exception.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
            "aurora-postgresql, 16.4, default.aurora-postgresql15",
            "aurora-mysql, 8.0.mysql_aurora.3.10.0, default.aurora-mysql5.7",
            "postgres, 16.8, default.postgres15",
            "mysql, 8.4.7, default.mysql8.0"
    })
    void createDbClusterRejectsManagedDefaultFromAnotherMajorVersion(
            String engine, String engineVersion, String groupName) {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbCluster("cluster", engine, engineVersion,
                        "admin", "password", "coredb", false, groupName));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
    }

    @Test
    void createDbClusterParameterGroupRoundTrip() {
        DbClusterParameterGroup created = rdsService.createDbClusterParameterGroup(
                "cpg1", "aurora-postgresql16", "test cluster group");

        assertEquals("cpg1", created.getDbClusterParameterGroupName());
        assertEquals("aurora-postgresql16", created.getDbParameterGroupFamily());

        DbClusterParameterGroup fetched = rdsService.getDbClusterParameterGroup("cpg1");
        assertEquals("cpg1", fetched.getDbClusterParameterGroupName());

        Collection<DbClusterParameterGroup> listed = rdsService.listDbClusterParameterGroups(null);
        List<String> names = listed.stream()
                .map(DbClusterParameterGroup::getDbClusterParameterGroupName)
                .toList();
        assertEquals(CURRENT_MANAGED_CLUSTER_PARAMETER_GROUP_FAMILIES.size() + 1, names.size());
        assertTrue(names.containsAll(CURRENT_MANAGED_CLUSTER_PARAMETER_GROUP_FAMILIES.stream()
                .map(family -> "default." + family)
                .toList()));
        assertTrue(names.contains("cpg1"));
    }

    @Test
    void createDbClusterParameterGroupRejectsManagedDefaultName() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbClusterParameterGroup(
                        "default.aurora-postgresql16", "aurora-postgresql16", "shadow"));

        assertEquals("DBParameterGroupAlreadyExists", exception.getErrorCode());
    }

    @Test
    void persistedManagedDefaultOverridesCatalogWithoutDuplication() {
        StorageBackend<String, DbClusterParameterGroup> clusterGroups = new InMemoryStorage<>();
        clusterGroups.put("default.aurora-postgresql16", new DbClusterParameterGroup(
                "default.aurora-postgresql16", "aurora-postgresql16", "persisted default"));
        RdsService service = newService(containerManager, proxyManager,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                clusterGroups, new InMemoryStorage<>());

        List<DbClusterParameterGroup> listed = List.copyOf(service.listDbClusterParameterGroups(null));
        assertEquals(CURRENT_MANAGED_CLUSTER_PARAMETER_GROUP_FAMILIES.size(), listed.size());
        List<DbClusterParameterGroup> matching = listed.stream()
                .filter(group -> "default.aurora-postgresql16".equals(
                        group.getDbClusterParameterGroupName()))
                .toList();
        assertEquals(1, matching.size());
        assertEquals("persisted default", matching.getFirst().getDescription());
    }

    @Test
    void parameterGroupsAreRegionScopedAndRegionalResourcesCannotClaimForeignGroups() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbParameterGroup("regional-pg", "postgres16", "east", "us-east-1");
        rdsService.createDbParameterGroup("regional-pg", "postgres16", "west", "us-west-2");
        rdsService.modifyDbParameterGroup(
                "regional-pg", Map.of("application_name", "east"), "us-east-1");
        rdsService.modifyDbParameterGroup(
                "regional-pg", Map.of("application_name", "west"), "us-west-2");

        assertEquals("east", rdsService.getDbParameterGroup(
                "regional-pg", "us-east-1").getParameters().get("application_name"));
        assertEquals("west", rdsService.getDbParameterGroup(
                "regional-pg", "us-west-2").getParameters().get("application_name"));

        rdsService.createDbClusterParameterGroup(
                "regional-cpg", "aurora-postgresql16", "east", "us-east-1");
        rdsService.createDbClusterParameterGroup(
                "regional-cpg", "aurora-postgresql16", "west", "us-west-2");
        rdsService.modifyDbClusterParameterGroup(
                "regional-cpg", Map.of("log_statement", "none"), "us-east-1");
        rdsService.modifyDbClusterParameterGroup(
                "regional-cpg", Map.of("log_statement", "all"), "us-west-2");

        assertEquals("none", rdsService.getDbClusterParameterGroup(
                "regional-cpg", "us-east-1").getParameters().get("log_statement"));
        assertEquals("all", rdsService.getDbClusterParameterGroup(
                "regional-cpg", "us-west-2").getParameters().get("log_statement"));

        rdsService.createDbParameterGroup(
                "east-only-pg", "postgres16", "east", "us-east-1");
        AwsException instanceGroupMissing = assertThrows(AwsException.class, () ->
                rdsService.createDbInstance(
                        "west-db", "postgres", "16.3", "admin", "secret", "app",
                        "db.t3.micro", 20, false, "east-only-pg", null, null,
                        null, false, false, null, Map.of(), List.of(), "us-west-2"));
        assertEquals("DBParameterGroupNotFound", instanceGroupMissing.getErrorCode());

        rdsService.createDbClusterParameterGroup(
                "east-only-cpg", "aurora-postgresql16", "east", "us-east-1");
        AwsException clusterGroupMissing = assertThrows(AwsException.class, () ->
                rdsService.createDbCluster(
                        "west-cluster", "aurora-postgresql", "16.3", "admin", "secret",
                        "app", false, "east-only-cpg", null, null, false, "us-west-2"));
        assertEquals("DBClusterParameterGroupNotFound", clusterGroupMissing.getErrorCode());

        rdsService.deleteDbParameterGroup("regional-pg", "us-east-1");
        rdsService.deleteDbClusterParameterGroup("regional-cpg", "us-east-1");
        assertEquals("west", rdsService.getDbParameterGroup(
                "regional-pg", "us-west-2").getDescription());
        assertEquals("west", rdsService.getDbClusterParameterGroup(
                "regional-cpg", "us-west-2").getDescription());
    }

    @Test
    void aParameterGroupPersistedWithoutAnArnGetsOneOnFirstRead() {
        // Only creation assigned the ARN, so a group written by an earlier version would never
        // get one — and the ARN is what a caller tags by, so the group could not be tagged at all.
        String accountId = "123456789012";
        InMemoryStorage<String, DbParameterGroup> rawParameterGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbClusterParameterGroup> rawClusterParameterGroups =
                new InMemoryStorage<>();
        rawParameterGroups.put("legacy-pg", new DbParameterGroup(
                "legacy-pg", "postgres16", "legacy"));
        rawClusterParameterGroups.put("legacy-cpg", new DbClusterParameterGroup(
                "legacy-cpg", "aurora-postgresql16", "legacy"));
        RdsService service = new RdsService(
                containerManager, proxyManager, ec2Service,
                new RegionResolver("us-east-1", accountId), config,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(rawParameterGroups, null, accountId),
                new AccountAwareStorageBackend<>(rawClusterParameterGroups, null, accountId),
                new InMemoryStorage<>());

        assertEquals("arn:aws:rds:us-east-1:" + accountId + ":pg:legacy-pg",
                service.getDbParameterGroup("legacy-pg", "us-east-1").getDbParameterGroupArn());
        assertEquals("arn:aws:rds:us-east-1:" + accountId + ":cluster-pg:legacy-cpg",
                service.getDbClusterParameterGroup("legacy-cpg", "us-east-1")
                        .getDbClusterParameterGroupArn());

        // And with an ARN it can be tagged, which is the point of backfilling it.
        service.addTagsToResource(
                "arn:aws:rds:us-east-1:" + accountId + ":cluster-pg:legacy-cpg",
                Map.of("env", "upgraded"), "us-east-1");
        assertEquals(Map.of("env", "upgraded"), service.listTagsForResource(
                "arn:aws:rds:us-east-1:" + accountId + ":cluster-pg:legacy-cpg", "us-east-1"));
    }

    @Test
    void rawLegacyParameterGroupsCannotBeClaimedByANonDefaultAccount() {
        String defaultAccount = "123456789012";
        String otherAccount = "222222222222";
        RegionResolver otherResolver = new RegionResolver("us-east-1", otherAccount);
        InMemoryStorage<String, DbParameterGroup> rawParameterGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbClusterParameterGroup> rawClusterParameterGroups =
                new InMemoryStorage<>();
        rawParameterGroups.put("legacy-pg", new DbParameterGroup(
                "legacy-pg", "postgres16", "legacy"));
        rawClusterParameterGroups.put("legacy-cpg", new DbClusterParameterGroup(
                "legacy-cpg", "aurora-postgresql16", "legacy"));
        RdsService service = new RdsService(
                containerManager, proxyManager, ec2Service, otherResolver, config,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(rawParameterGroups, null, defaultAccount),
                new AccountAwareStorageBackend<>(rawClusterParameterGroups, null, defaultAccount),
                new InMemoryStorage<>());

        assertEquals("DBParameterGroupNotFound", assertThrows(AwsException.class, () ->
                service.getDbParameterGroup("legacy-pg", "us-east-1")).getErrorCode());
        assertEquals("DBClusterParameterGroupNotFound", assertThrows(AwsException.class, () ->
                service.getDbClusterParameterGroup("legacy-cpg", "us-east-1")).getErrorCode());
        assertTrue(rawParameterGroups.get("legacy-pg").isPresent());
        assertTrue(rawClusterParameterGroups.get("legacy-cpg").isPresent());
        assertTrue(rawParameterGroups.get(otherAccount + "/us-east-1::legacy-pg").isEmpty());
        assertTrue(rawClusterParameterGroups.get(
                otherAccount + "/us-east-1::legacy-cpg").isEmpty());
    }

    @Test
    void unfilteredGroupListsMigrateSafeRawLegacyStateForTheDefaultAccount() {
        String accountId = "123456789012";
        InMemoryStorage<String, DbSubnetGroup> rawSubnetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbParameterGroup> rawParameterGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbClusterParameterGroup> rawClusterParameterGroups =
                new InMemoryStorage<>();
        DbSubnetGroup subnetGroup = new DbSubnetGroup(
                "legacy-subnets", "legacy", "vpc-default", PROXY_SUBNET_IDS,
                Map.of("subnet-default-a", "us-east-1a", "subnet-default-b", "us-east-1b"));
        subnetGroup.setDbSubnetGroupArn(
                "arn:aws:rds:us-east-1:" + accountId + ":subgrp:legacy-subnets");
        rawSubnetGroups.put("legacy-subnets", subnetGroup);
        rawParameterGroups.put("legacy-pg", new DbParameterGroup(
                "legacy-pg", "postgres16", "legacy"));
        rawClusterParameterGroups.put("legacy-cpg", new DbClusterParameterGroup(
                "legacy-cpg", "aurora-postgresql16", "legacy"));
        RdsService service = new RdsService(
                containerManager, proxyManager, ec2Service, regionResolver, config,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(rawParameterGroups, null, accountId),
                new AccountAwareStorageBackend<>(rawClusterParameterGroups, null, accountId),
                new AccountAwareStorageBackend<>(rawSubnetGroups, null, accountId));

        assertTrue(service.listDbSubnetGroups(null, "us-east-1").stream()
                .anyMatch(group -> "legacy-subnets".equals(group.getDbSubnetGroupName())));
        assertTrue(service.listDbParameterGroups(null, "us-east-1").stream()
                .anyMatch(group -> "legacy-pg".equals(group.getDbParameterGroupName())));
        assertTrue(service.listDbClusterParameterGroups(null, "us-east-1").stream()
                .anyMatch(group -> "legacy-cpg".equals(group.getDbClusterParameterGroupName())));
        assertTrue(rawSubnetGroups.get("legacy-subnets").isEmpty());
        assertTrue(rawParameterGroups.get("legacy-pg").isEmpty());
        assertTrue(rawClusterParameterGroups.get("legacy-cpg").isEmpty());
        assertTrue(rawSubnetGroups.get(accountId + "/us-east-1::legacy-subnets").isPresent());
        assertTrue(rawParameterGroups.get(accountId + "/us-east-1::legacy-pg").isPresent());
        assertTrue(rawClusterParameterGroups.get(accountId + "/us-east-1::legacy-cpg").isPresent());
    }

    @Test
    void corruptCanonicalGroupStateFailsClosedAndCreateDoesNotOverwriteIt() {
        String accountId = "123456789012";
        InMemoryStorage<String, DbSubnetGroup> rawSubnetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbParameterGroup> rawParameterGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbClusterParameterGroup> rawClusterParameterGroups =
                new InMemoryStorage<>();
        DbSubnetGroup wrongSubnetName = new DbSubnetGroup(
                "other-subnets", "corrupt", "vpc-default", PROXY_SUBNET_IDS, Map.of());
        wrongSubnetName.setDbSubnetGroupArn(
                "arn:aws:rds:us-east-1:" + accountId + ":subgrp:expected-subnets");
        DbParameterGroup wrongParameterName = new DbParameterGroup(
                "other-pg", "postgres16", "corrupt");
        wrongParameterName.setRegion("us-east-1");
        DbParameterGroup legacyParameter = new DbParameterGroup(
                "expected-pg", "postgres16", "legacy");
        DbClusterParameterGroup wrongClusterParameterName = new DbClusterParameterGroup(
                "other-cpg", "aurora-postgresql16", "corrupt");
        wrongClusterParameterName.setRegion("us-east-1");
        rawSubnetGroups.put(accountId + "/us-east-1::expected-subnets", wrongSubnetName);
        rawParameterGroups.put(accountId + "/us-east-1::expected-pg", wrongParameterName);
        rawParameterGroups.put(accountId + "/expected-pg", legacyParameter);
        rawClusterParameterGroups.put(
                accountId + "/us-east-1::expected-cpg", wrongClusterParameterName);
        RdsService service = new RdsService(
                containerManager, proxyManager, ec2Service, regionResolver, config,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(rawParameterGroups, null, accountId),
                new AccountAwareStorageBackend<>(rawClusterParameterGroups, null, accountId),
                new AccountAwareStorageBackend<>(rawSubnetGroups, null, accountId));

        assertThrows(AwsException.class, () ->
                service.getDbSubnetGroup("expected-subnets", "us-east-1"));
        assertThrows(AwsException.class, () ->
                service.getDbParameterGroup("expected-pg", "us-east-1"));
        assertThrows(AwsException.class, () ->
                service.getDbClusterParameterGroup("expected-cpg", "us-east-1"));
        assertEquals("DBSubnetGroupAlreadyExists", assertThrows(AwsException.class, () ->
                service.createDbSubnetGroup(
                        "expected-subnets", "new", PROXY_SUBNET_IDS, "us-east-1"))
                .getErrorCode());
        assertEquals("DBParameterGroupAlreadyExists", assertThrows(AwsException.class, () ->
                service.createDbParameterGroup(
                        "expected-pg", "postgres16", "new", "us-east-1"))
                .getErrorCode());
        assertEquals("DBParameterGroupAlreadyExists", assertThrows(AwsException.class, () ->
                service.createDbClusterParameterGroup(
                        "expected-cpg", "aurora-postgresql16", "new", "us-east-1"))
                .getErrorCode());
        assertSame(wrongSubnetName, rawSubnetGroups.get(
                accountId + "/us-east-1::expected-subnets").orElseThrow());
        assertSame(wrongParameterName, rawParameterGroups.get(
                accountId + "/us-east-1::expected-pg").orElseThrow());
        assertSame(legacyParameter, rawParameterGroups.get(
                accountId + "/expected-pg").orElseThrow());
        assertSame(wrongClusterParameterName, rawClusterParameterGroups.get(
                accountId + "/us-east-1::expected-cpg").orElseThrow());
    }

    @Test
    void createDbClusterParameterGroupRejectsDuplicate() {
        rdsService.createDbClusterParameterGroup("cpg1", "aurora-postgresql16", "desc");

        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbClusterParameterGroup("cpg1", "aurora-postgresql16", "desc"));

        assertEquals("DBParameterGroupAlreadyExists", exception.getErrorCode());
    }

    @Test
    void createDbSubnetGroupRejectsDuplicateWithModelCode() {
        rdsService.createDbSubnetGroup("my-subnet-group", "desc", List.of("subnet-default-a", "subnet-default-b"));

        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbSubnetGroup("my-subnet-group", "desc", List.of("subnet-default-a", "subnet-default-b")));

        assertEquals("DBSubnetGroupAlreadyExists", exception.getErrorCode());
    }

    @Test
    void createDbSubnetGroupPopulatesArn() {
        DbSubnetGroup group = rdsService.createDbSubnetGroup("my-subnet-group", "desc",
                List.of("subnet-default-a", "subnet-default-b"));

        assertEquals("arn:aws:rds:us-east-1:123456789012:subgrp:my-subnet-group", group.getDbSubnetGroupArn());
    }

    @Test
    void createDbSubnetGroupUsesSuppliedRegionForSubnetLookup() {
        List<String> subnetIds = List.of("subnet-west-a", "subnet-west-b");
        when(ec2Service.describeSubnets(eq("us-west-2"), eq(subnetIds), eq(Map.of())))
                .thenReturn(List.of(
                        subnet("subnet-west-a", "vpc-west", "us-west-2a"),
                        subnet("subnet-west-b", "vpc-west", "us-west-2b")));

        DbSubnetGroup group = rdsService.createDbSubnetGroup("west-subnets", "desc", subnetIds, "us-west-2");

        assertEquals("vpc-west", group.getVpcId());
        assertEquals("arn:aws:rds:us-west-2:123456789012:subgrp:west-subnets", group.getDbSubnetGroupArn());
        verify(ec2Service).describeSubnets(eq("us-west-2"), eq(subnetIds), eq(Map.of()));
    }

    @Test
    void createDbSubnetGroupRequiresSubnetIdsWithMissingParameter() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbSubnetGroup("my-subnet-group", "desc", List.of()));

        assertEquals("MissingParameter", exception.getErrorCode());
    }

    @Test
    void createDbInstanceMultiAzRequiresSubnetGroupCoverageAcrossAvailabilityZones() {
        StorageBackend<String, DbSubnetGroup> subnetGroups = new InMemoryStorage<>();
        DbSubnetGroup singleAzGroup = new DbSubnetGroup(
                "single-az-group",
                "desc",
                "vpc-default",
                List.of("subnet-a", "subnet-b"),
                Map.of("subnet-a", "us-east-1a", "subnet-b", "us-east-1a"));
        singleAzGroup.setDbSubnetGroupArn(
                "arn:aws:rds:us-east-1:123456789012:subgrp:single-az-group");
        subnetGroups.put("single-az-group", singleAzGroup);
        RdsService service = newService(containerManager, proxyManager,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), subnetGroups);

        AwsException exception = assertThrows(AwsException.class, () ->
                service.createDbInstance("mydb", "postgres", "13",
                        "admin", "password", "dbname", "db.t3.micro",
                        20, false, null, "single-az-group", null, null, true));

        assertEquals("DBSubnetGroupDoesNotCoverEnoughAZs", exception.getErrorCode());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createDbClusterRejectsAvailabilityZoneWhenMultiAzEnabled() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbCluster("cluster1", "postgres", "13",
                        "admin", "password", "dbname", false,
                        null, null, "us-east-1a", true));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void resolveDbSubnetGroupViewReturnsStoredCustomGroup() {
        rdsService.createDbSubnetGroup("my-subnet-group", "desc", List.of("subnet-default-a", "subnet-default-b"));

        DbSubnetGroup group = rdsService.resolveDbSubnetGroupView("my-subnet-group");

        assertEquals("my-subnet-group", group.getDbSubnetGroupName());
        assertEquals("arn:aws:rds:us-east-1:123456789012:subgrp:my-subnet-group", group.getDbSubnetGroupArn());
    }

    @Test
    void resolveDbSubnetGroupViewReturnsDefaultGroupForBlankName() {
        DbSubnetGroup group = rdsService.resolveDbSubnetGroupView(null);

        assertEquals("default", group.getDbSubnetGroupName());
        assertEquals("arn:aws:rds:us-east-1:123456789012:subgrp:default", group.getDbSubnetGroupArn());
    }

    @Test
    void resolveDbSubnetGroupViewUsesSuppliedRegionForDefaultGroup() {
        when(ec2Service.describeSubnets(eq("us-west-2"), anyList(), any()))
                .thenReturn(List.of(
                        subnet("subnet-west-a", "vpc-west", "us-west-2a"),
                        subnet("subnet-west-b", "vpc-west", "us-west-2b")));

        DbSubnetGroup group = rdsService.resolveDbSubnetGroupView(null, "us-west-2");

        assertEquals("default", group.getDbSubnetGroupName());
        assertEquals("vpc-west", group.getVpcId());
        assertEquals("arn:aws:rds:us-west-2:123456789012:subgrp:default", group.getDbSubnetGroupArn());
        assertEquals(Map.of("subnet-west-a", "us-west-2a", "subnet-west-b", "us-west-2b"),
                group.getSubnetAvailabilityZones());
    }

    @Test
    void getDbSubnetGroupUsesSuppliedRegionForDefaultGroup() {
        when(ec2Service.describeSubnets(eq("us-west-2"), anyList(), any()))
                .thenReturn(List.of(
                        subnet("subnet-west-a", "vpc-west", "us-west-2a"),
                        subnet("subnet-west-b", "vpc-west", "us-west-2b")));

        DbSubnetGroup group = rdsService.getDbSubnetGroup("default", "us-west-2");

        assertEquals("default", group.getDbSubnetGroupName());
        assertEquals("vpc-west", group.getVpcId());
        assertEquals("arn:aws:rds:us-west-2:123456789012:subgrp:default", group.getDbSubnetGroupArn());
    }

    @Test
    void modifyDbClusterParameterGroupAppliesParameters() {
        rdsService.createDbClusterParameterGroup("cpg1", "aurora-postgresql16", "desc");

        DbClusterParameterGroup modified = rdsService.modifyDbClusterParameterGroup(
                "cpg1", java.util.Map.of("log_statement", "all", "shared_preload_libraries", "pg_stat_statements"));

        assertEquals("all", modified.getParameters().get("log_statement"));
        assertEquals("pg_stat_statements", modified.getParameters().get("shared_preload_libraries"));
    }

    @Test
    void modifyManagedDefaultClusterParameterGroupIsRejectedWithoutPersistingChanges() {
        String name = "default.aurora-postgresql16";

        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.modifyDbClusterParameterGroup(
                        name, Map.of("log_statement", "all")));

        assertEquals("InvalidDBParameterGroupState", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
        assertTrue(rdsService.getDbClusterParameterGroup(name).getParameters().isEmpty());
    }

    @Test
    void deleteManagedDefaultClusterParameterGroupIsRejectedAndGroupRemainsResolvable() {
        String name = "default.aurora-postgresql16";

        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.deleteDbClusterParameterGroup(name));

        assertEquals("InvalidDBParameterGroupState", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
        assertEquals(name, rdsService.getDbClusterParameterGroup(name).getDbClusterParameterGroupName());
    }

    @Test
    void deleteDbClusterParameterGroupMissingThrows() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.deleteDbClusterParameterGroup("nonexistent"));

        assertEquals("DBClusterParameterGroupNotFound", exception.getErrorCode());
        assertEquals("DBClusterParameterGroupName doesn't refer to an existing DB cluster parameter group.", exception.getMessage());
    }

    @Test
    void getDbClusterParameterGroupMissingThrows() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.getDbClusterParameterGroup("nonexistent"));

        assertEquals("DBClusterParameterGroupNotFound", exception.getErrorCode());
        assertEquals("DBClusterParameterGroupName doesn't refer to an existing DB cluster parameter group.", exception.getMessage());
    }

    @Test
    void restorePersistedRuntimeReusesLegacyStandaloneStorageAndProxyPort() {
        StorageBackend<String, DbInstance> instances = new InMemoryStorage<>();
        StorageBackend<String, DbCluster> clusters = new InMemoryStorage<>();
        StorageBackend<String, DbParameterGroup> parameterGroups = new InMemoryStorage<>();
        StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups = new InMemoryStorage<>();

        when(containerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("initial-container", "mydb", "localhost", 5432));

        RdsService initialService = newService(containerManager, proxyManager,
                instances, clusters, parameterGroups, clusterParameterGroups, new InMemoryStorage<>());
        DbInstance created = initialService.createDbInstance("mydb", "postgres", "16.3",
                "admin", "secret", "app", "db.t3.micro",
                20, false, null, null, null, null, false);

        String persistedVolumeId = created.getVolumeId();
        int persistedProxyPort = created.getProxyPort();
        created.setContainerStorageResourceId(null);
        created.setDockerVolumeName("floci-rds-" + persistedVolumeId);

        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        when(restoredContainerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("restored-container", "mydb", "127.0.0.1", 15432));

        RdsService restoredService = newService(restoredContainerManager, restoredProxyManager,
                instances, clusters, parameterGroups, clusterParameterGroups, new InMemoryStorage<>());
        restoredService.restorePersistedRuntime();

        DbInstance restored = restoredService.getDbInstance("mydb");
        assertEquals(persistedVolumeId, restored.getVolumeId());
        assertEquals("mydb", restored.getContainerStorageResourceId());
        assertEquals("floci-rds-" + persistedVolumeId, restored.getDockerVolumeName());
        assertEquals(persistedProxyPort, restored.getProxyPort());
        assertEquals(persistedProxyPort, restored.getEndpoint().port());
        assertEquals("restored-container", restored.getContainerId());
        assertEquals("127.0.0.1", restored.getContainerHost());
        assertEquals(15432, restored.getContainerPort());

        verify(restoredContainerManager).start(
                eq(restored.getDbInstanceArn()), eq("mydb"),
                eq(restored.getContainerStorageResourceId()),
                eq(restored.getDockerVolumeName()), eq(DatabaseEngine.POSTGRES),
                eq("postgres:16.3-alpine"), eq("admin"), eq("secret"), eq("app"));
        verify(restoredProxyManager).startProxy(
                eq("rds-resource:" + restored.getDbInstanceArn()), eq(DatabaseEngine.POSTGRES),
                eq(false), eq(persistedProxyPort), eq("127.0.0.1"), eq(15432), any(),
                eq("admin"), eq("secret"), eq("app"), any());

        restoredService.deleteDbInstance("mydb");
        verify(restoredContainerManager).removeVolume(
                restored.getDbInstanceArn(), "mydb", "floci-rds-" + persistedVolumeId);
    }

    @Test
    void restorePersistedRuntimeRestoresClusterAndMemberInstance() {
        StorageBackend<String, DbInstance> instances = new InMemoryStorage<>();
        StorageBackend<String, DbCluster> clusters = new InMemoryStorage<>();
        StorageBackend<String, DbParameterGroup> parameterGroups = new InMemoryStorage<>();
        StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups = new InMemoryStorage<>();

        when(containerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("initial-cluster-container", "cluster1", "localhost", 5432));

        RdsService initialService = newService(containerManager, proxyManager,
                instances, clusters, parameterGroups, clusterParameterGroups, new InMemoryStorage<>());
        DbCluster cluster = initialService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null, null, null, false);
        DbInstance member = initialService.createDbInstance("member1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", "db.t3.medium",
                20, false, null, null, "cluster1", null, false);

        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        when(restoredContainerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("restored-cluster-container", "cluster1", "127.0.0.1", 15432));

        RdsService restoredService = newService(restoredContainerManager, restoredProxyManager,
                instances, clusters, parameterGroups, clusterParameterGroups, new InMemoryStorage<>());
        restoredService.restorePersistedRuntime();

        DbCluster restoredCluster = restoredService.getDbCluster("cluster1");
        DbInstance restoredMember = restoredService.getDbInstance("member1");

        assertEquals(cluster.getVolumeId(), restoredCluster.getVolumeId());
        assertEquals(cluster.getProxyPort(), restoredCluster.getProxyPort());
        assertEquals(member.getProxyPort(), restoredMember.getProxyPort());
        assertEquals("restored-cluster-container", restoredCluster.getContainerId());
        assertEquals("restored-cluster-container", restoredMember.getContainerId());
        assertEquals("127.0.0.1", restoredMember.getContainerHost());
        assertEquals(15432, restoredMember.getContainerPort());

        verify(restoredContainerManager).start(
                eq(restoredCluster.getDbClusterArn()), eq("cluster1"),
                eq(restoredCluster.getContainerStorageResourceId()),
                eq(restoredCluster.getDockerVolumeName()), eq(DatabaseEngine.POSTGRES),
                eq("postgres:16.3-alpine"), eq("admin"), eq("secret"), eq("app"));
        verify(restoredProxyManager).startProxy(
                eq("rds-resource:" + restoredCluster.getDbClusterArn()), eq(DatabaseEngine.POSTGRES),
                eq(false), eq(cluster.getProxyPort()), eq("127.0.0.1"), eq(15432), any(),
                eq("admin"), eq("secret"), eq("app"), any());
        verify(restoredProxyManager).startProxy(
                eq("rds-resource:" + restoredMember.getDbInstanceArn()), eq(DatabaseEngine.POSTGRES),
                eq(false), eq(member.getProxyPort()), eq("127.0.0.1"), eq(15432), any(),
                eq("admin"), eq("secret"), eq("app"), any());
    }

    @Test
    void failedInstanceRestoreClearsRuntimeStateAndReleasesItsEndpointPort() {
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        instances.put("broken", persistedInstance("broken", "123456789012", "secret", 7000));
        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        String runtimeId = "arn:aws:rds:us-east-1:123456789012:db:broken";
        RdsContainerHandle restoredHandle = new RdsContainerHandle(
                "restored-container", runtimeId, "broken", "127.0.0.1", 15432);
        RdsContainerHandle replacementHandle = new RdsContainerHandle(
                "replacement-container", "arn:aws:rds:us-east-1:123456789012:db:replacement",
                "replacement", "127.0.0.1", 15433);
        when(restoredContainerManager.start(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(restoredHandle, replacementHandle);
        org.mockito.Mockito.doThrow(new IllegalStateException("relay failed"))
                .doNothing()
                .doNothing()
                .when(restoredProxyManager).startProxy(
                        any(), any(), anyBoolean(), anyInt(), any(), anyInt(),
                        any(), any(), any(), any(), any());
        org.mockito.Mockito.doThrow(new IllegalStateException("cleanup failed"))
                .doNothing()
                .doNothing()
                .when(restoredProxyManager).stopProxy(any());
        org.mockito.Mockito.doThrow(new IllegalStateException("restore container cleanup failed"))
                .doThrow(new IllegalStateException("delete container cleanup failed"))
                .doNothing()
                .when(restoredContainerManager).stop(org.mockito.ArgumentMatchers.argThat(
                        handle -> runtimeId.equals(handle.getRuntimeId())));
        RdsService restoredService = newService(
                restoredContainerManager, restoredProxyManager, instances,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        restoredService.restorePersistedRuntime();

        DbInstance failed = restoredService.getDbInstance("broken");
        assertEquals(DbInstanceStatus.FAILED, failed.getStatus());
        assertNull(failed.getEndpoint());
        assertEquals("restored-container", failed.getContainerId());
        assertNull(failed.getContainerHost());
        assertEquals(0, failed.getContainerPort());
        assertEquals(0, failed.getProxyPort());

        assertThrows(IllegalStateException.class, () ->
                restoredService.deleteDbInstance("broken"));
        assertEquals(DbInstanceStatus.DELETING,
                restoredService.getDbInstance("broken").getStatus());
        verify(restoredContainerManager, never()).removeVolume(any(), any(), any());

        assertDoesNotThrow(() -> restoredService.deleteDbInstance("broken"));
        assertThrows(AwsException.class, () -> restoredService.getDbInstance("broken"));
        verify(restoredContainerManager, times(3)).stop(
                org.mockito.ArgumentMatchers.argThat(
                        handle -> runtimeId.equals(handle.getRuntimeId())));
        verify(restoredContainerManager).removeVolume(any(), any(), any());

        DbInstance replacement = restoredService.createDbInstance(
                "replacement", "postgres", "16.3", "admin", "secret", "app",
                "db.t3.micro", 20, false, null, null, null);
        assertEquals(7000, replacement.getEndpoint().port());
    }

    @Test
    void failedInstanceStartPreservesPersistedContainerIdentityForDeletion() {
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        DbInstance persisted = persistedInstance(
                "broken", "123456789012", "secret", 7000);
        persisted.setContainerId("persisted-container");
        instances.put("broken", persisted);
        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        when(restoredContainerManager.start(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Docker start failed"));
        RdsService restoredService = newService(
                restoredContainerManager, mock(RdsProxyManager.class), instances,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        restoredService.restorePersistedRuntime();

        DbInstance failed = restoredService.getDbInstance("broken");
        assertEquals(DbInstanceStatus.FAILED, failed.getStatus());
        assertNull(failed.getEndpoint());
        assertEquals("persisted-container", failed.getContainerId());
        assertNull(failed.getContainerHost());
        assertEquals(0, failed.getContainerPort());

        assertDoesNotThrow(() -> restoredService.deleteDbInstance("broken"));
        verify(restoredContainerManager).stop(
                org.mockito.ArgumentMatchers.argThat(handle ->
                        "persisted-container".equals(handle.getContainerId())
                                && persisted.getDbInstanceArn().equals(handle.getRuntimeId())));
        assertThrows(AwsException.class, () -> restoredService.getDbInstance("broken"));
    }

    @Test
    void restorePortExhaustionMarksEachUnrestorableInstanceAndContinues() {
        when(config.services().rds().mock()).thenReturn(true);
        when(config.services().rds().proxyBasePort()).thenReturn(7000);
        when(config.services().rds().proxyMaxPort()).thenReturn(7000);
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        instances.put("first", persistedInstance(
                "first", "123456789012", "secret", 7000));
        instances.put("second", persistedInstance(
                "second", "123456789012", "secret", 7000));
        RdsService restoredService = newService(
                containerManager, proxyManager, instances, new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());

        assertDoesNotThrow(restoredService::restorePersistedRuntime);

        Collection<DbInstance> restored = restoredService.listDbInstances(null);
        assertEquals(2, restored.size());
        assertEquals(1, restored.stream()
                .filter(instance -> instance.getStatus() == DbInstanceStatus.AVAILABLE)
                .count());
        DbInstance failed = restored.stream()
                .filter(instance -> instance.getStatus() == DbInstanceStatus.FAILED)
                .findFirst()
                .orElseThrow();
        assertNull(failed.getEndpoint());
        assertEquals(0, failed.getProxyPort());
    }

    @Test
    void failedClusterRestoreClearsRuntimeStateAndReleasesItsEndpointPort() {
        InMemoryStorage<String, DbCluster> clusters = new InMemoryStorage<>();
        clusters.put("cluster1", persistedCluster("123456789012", "secret", 7000));
        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        String runtimeId = "arn:aws:rds:us-east-1:123456789012:cluster:cluster1";
        RdsContainerHandle restoredHandle = new RdsContainerHandle(
                "restored-container", runtimeId, "cluster1", "127.0.0.1", 15432);
        RdsContainerHandle replacementHandle = new RdsContainerHandle(
                "replacement-container", "arn:aws:rds:us-east-1:123456789012:cluster:replacement",
                "replacement", "127.0.0.1", 15433);
        when(restoredContainerManager.start(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(restoredHandle, replacementHandle);
        org.mockito.Mockito.doThrow(new IllegalStateException("relay failed"))
                .doNothing()
                .when(restoredProxyManager).startProxy(
                        any(), any(), anyBoolean(), anyInt(), any(), anyInt(),
                        any(), any(), any(), any(), any());
        org.mockito.Mockito.doThrow(new IllegalStateException("restore container cleanup failed"))
                .doThrow(new IllegalStateException("delete container cleanup failed"))
                .doNothing()
                .when(restoredContainerManager).stop(org.mockito.ArgumentMatchers.argThat(
                        handle -> runtimeId.equals(handle.getRuntimeId())));
        RdsService restoredService = newService(
                restoredContainerManager, restoredProxyManager, new InMemoryStorage<>(),
                clusters, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>());

        restoredService.restorePersistedRuntime();

        DbCluster failed = restoredService.getDbCluster("cluster1");
        assertEquals(DbInstanceStatus.FAILED, failed.getStatus());
        assertNull(failed.getEndpoint());
        assertNull(failed.getReaderEndpoint());
        assertEquals("restored-container", failed.getContainerId());
        assertNull(failed.getContainerHost());
        assertEquals(0, failed.getContainerPort());
        assertEquals(0, failed.getProxyPort());

        assertThrows(IllegalStateException.class, () ->
                restoredService.deleteDbCluster("cluster1"));
        assertEquals(DbInstanceStatus.DELETING,
                restoredService.getDbCluster("cluster1").getStatus());
        verify(restoredContainerManager, never()).removeVolume(any(), any(), any());

        assertDoesNotThrow(() -> restoredService.deleteDbCluster("cluster1"));
        assertThrows(AwsException.class, () -> restoredService.getDbCluster("cluster1"));
        verify(restoredContainerManager, times(3)).stop(
                org.mockito.ArgumentMatchers.argThat(
                        handle -> runtimeId.equals(handle.getRuntimeId())));
        verify(restoredContainerManager).removeVolume(any(), any(), any());

        DbCluster replacement = restoredService.createDbCluster(
                "replacement", "aurora-postgresql", "16.3", "admin", "secret",
                "app", false, null);
        assertEquals(7000, replacement.getEndpoint().port());
    }

    @Test
    void createDbProxyPopulatesEndpointArnAndDefaultPort() {
        DbProxy proxy = rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of("sg-a"), PROXY_AUTH, Map.of());

        assertEquals("app-proxy", proxy.getDbProxyName());
        assertEquals("available", proxy.getStatus());
        assertEquals(5432, proxy.getProxyPort());   // POSTGRESQL default listener port
        assertNotNull(proxy.getDbProxyResourceId());
        assertTrue(proxy.getDbProxyResourceId().startsWith("prx-"));
        assertEquals("arn:aws:rds:us-east-1:123456789012:db-proxy:" + proxy.getDbProxyResourceId(),
                proxy.getDbProxyArn());
        assertEquals("vpc-default", proxy.getVpcId());
        assertEquals(1, rdsService.listDbProxies("app-proxy").size());
        DbProxyTargetGroup targetGroup = rdsService.describeDbProxyTargetGroups("app-proxy").iterator().next();
        assertEquals("default", targetGroup.getTargetGroupName());
        assertTrue(targetGroup.getTargets().isEmpty());
        assertTrue(targetGroup.getTargetGroupArn().contains(":target-group:prx-tg-"));
    }

    @Test
    void createDbProxyRejectsDuplicate() {
        rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());

        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of()));

        assertEquals("DBProxyAlreadyExistsFault", exception.getErrorCode());
    }

    @Test
    void createDbProxyReservesDistinctInternalListenerPorts() {
        // Listener ports must never collide even though externally routing multiple same-engine
        // proxy hostnames remains a separate concern.
        DbProxy first = rdsService.createDbProxy("proxy-a", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        DbProxy second = rdsService.createDbProxy("proxy-b", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());

        assertEquals(5432, first.getProxyPort());   // first proxy keeps the clean engine default
        assertNotEquals(first.getProxyPort(), second.getProxyPort());
    }

    @Test
    void registerDbProxyTargetsCreatesDefaultTargetGroupForCluster() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null);
        rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());

        DbProxyTargetGroup tg = rdsService.registerDbProxyTargets("app-proxy", null,
                List.of("cluster1"), List.of(), 90, 40);

        assertEquals("app-proxy", tg.getDbProxyName());
        assertEquals("default", tg.getTargetGroupName());   // blank TargetGroupName defaults to "default"
        assertEquals(90, tg.getMaxConnectionsPercent());
        assertEquals(40, tg.getMaxIdleConnectionsPercent());
        assertEquals(1, tg.getTargets().size());
        DbProxyTarget target = tg.getTargets().get(0);
        assertEquals("TRACKED_CLUSTER", target.getType());
        assertEquals("cluster1", target.getRdsResourceId());
        // The registered target group and target are read back through the describe APIs.
        assertEquals(1, rdsService.describeDbProxyTargetGroups("app-proxy").size());
        assertEquals(1, rdsService.describeDbProxyTargets("app-proxy", "default").size());
    }

    @Test
    void createDbProxyValidatesRequiredInputsAndTimeout() {
        AwsException missingName = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy(null, "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidParameterValue", missingName.getErrorCode());

        AwsException invalidEngine = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "ORACLE", true, false, PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidParameterValue", invalidEngine.getErrorCode());

        AwsException missingRole = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, null,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidParameterValue", missingRole.getErrorCode());

        AwsException missingSubnets = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        List.of(), List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidParameterValue", missingSubnets.getErrorCode());

        AwsException oneSubnet = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        List.of("subnet-a"), List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidParameterValue", oneSubnet.getErrorCode());
        assertEquals(400, oneSubnet.getHttpStatus());
        assertTrue(oneSubnet.getMessage().contains("at least two distinct subnet IDs"));
        assertTrue(rdsService.listDbProxies(null).isEmpty());

        AwsException duplicateSubnets = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        List.of("subnet-a", "subnet-a"), List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidParameterValue", duplicateSubnets.getErrorCode());

        AwsException invalidTimeout = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, 0, false, Map.of(), "us-east-1"));
        assertEquals("InvalidParameterValue", invalidTimeout.getErrorCode());

        AwsException missingSubnet = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        List.of("subnet-missing-a", "subnet-missing-b"),
                        List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidSubnet", missingSubnet.getErrorCode());

        when(ec2Service.describeSubnets(eq("us-east-1"),
                eq(List.of("subnet-same-az-a", "subnet-same-az-b")), any()))
                .thenReturn(List.of(
                        subnet("subnet-same-az-a", "vpc-a", "us-east-1a"),
                        subnet("subnet-same-az-b", "vpc-a", "us-east-1a")));
        AwsException sameAvailabilityZone = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        List.of("subnet-same-az-a", "subnet-same-az-b"),
                        List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidSubnet", sameAvailabilityZone.getErrorCode());

        when(ec2Service.describeSubnets(eq("us-east-1"),
                eq(List.of("subnet-vpc-a", "subnet-vpc-b")), any()))
                .thenReturn(List.of(
                        subnet("subnet-vpc-a", "vpc-a", "us-east-1a"),
                        subnet("subnet-vpc-b", "vpc-b", "us-east-1b")));
        AwsException mixedVpc = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        List.of("subnet-vpc-a", "subnet-vpc-b"),
                        List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidSubnet", mixedVpc.getErrorCode());
    }

    @Test
    void createDbProxyValidatesAndPersistsDefaultAuthScheme() {
        DbProxy iamProxy = rdsService.createDbProxy(
                "iam-proxy", "POSTGRESQL", true, true, "IAM_AUTH", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), List.of(), 1800, false, Map.of(), "us-east-1");

        assertEquals("IAM_AUTH", iamProxy.getDefaultAuthScheme());
        assertTrue(iamProxy.isIamAuth());
        assertTrue(iamProxy.getAuth().isEmpty());
        assertNotNull(iamProxy.getUpdatedAt());

        AwsException missingAuth = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy(
                        "none-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), List.of(), 1800, false, Map.of(), "us-east-1"));
        assertEquals("InvalidParameterValue", missingAuth.getErrorCode());

        AwsException invalidScheme = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy(
                        "bad-proxy", "POSTGRESQL", true, false, "PASSWORD", PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, 1800, false, Map.of(), "us-east-1"));
        assertEquals("InvalidParameterValue", invalidScheme.getErrorCode());

        AwsException sqlServerIamAuth = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy(
                        "sqlserver-proxy", "SQLSERVER", true, true, "IAM_AUTH", PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), List.of(), 1800, false, Map.of(), "us-east-1"));
        assertEquals("InvalidParameterValue", sqlServerIamAuth.getErrorCode());

        AwsException blankScheme = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy(
                        "blank-proxy", "POSTGRESQL", true, false, "", PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, 1800, false,
                        Map.of(), "us-east-1"));
        assertEquals("InvalidParameterValue", blankScheme.getErrorCode());
    }

    @Test
    void createAndModifyDbProxyValidateUserAuthConfig() {
        List<DbProxyAuth> invalidEntries = List.of(
                new DbProxyAuth("PASSWORD", PROXY_AUTH.getFirst().getSecretArn(),
                        "DISABLED", null, null),
                new DbProxyAuth("SECRETS", PROXY_AUTH.getFirst().getSecretArn(),
                        "OPTIONAL", null, null),
                new DbProxyAuth("SECRETS", PROXY_AUTH.getFirst().getSecretArn(),
                        "ENABLED", null, null),
                new DbProxyAuth("SECRETS", PROXY_AUTH.getFirst().getSecretArn(),
                        "DISABLED", "KERBEROS", null),
                new DbProxyAuth("SECRETS", PROXY_AUTH.getFirst().getSecretArn(),
                        "DISABLED", null, ""),
                new DbProxyAuth("SECRETS", "too-short", "DISABLED", null, null),
                proxyAuthWithUserName(""),
                proxyAuthWithUserName("u".repeat(129)));

        for (DbProxyAuth invalidEntry : invalidEntries) {
            AwsException exception = assertThrows(AwsException.class, () ->
                    rdsService.createDbProxy(
                            "invalid-auth-proxy", "POSTGRESQL", true, false, "NONE",
                            PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), List.of(invalidEntry),
                            1800, false, Map.of(), "us-east-1"));
            assertEquals("InvalidParameterValue", exception.getErrorCode());
        }

        DbProxyAuth longDescription = new DbProxyAuth(
                "SECRETS", PROXY_AUTH.getFirst().getSecretArn(), "DISABLED", null,
                "d".repeat(1_001));
        assertThrows(AwsException.class, () -> rdsService.createDbProxy(
                "long-description-proxy", "POSTGRESQL", true, false, "NONE",
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), List.of(longDescription),
                1800, false, Map.of(), "us-east-1"));

        List<DbProxyAuth> tooManyEntries = java.util.Collections.nCopies(201, PROXY_AUTH.getFirst());
        assertThrows(AwsException.class, () -> rdsService.createDbProxy(
                "too-many-auth-proxy", "POSTGRESQL", true, false, "NONE",
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), tooManyEntries,
                1800, false, Map.of(), "us-east-1"));

        DbProxyAuth sqlServerAuth = new DbProxyAuth(
                "SECRETS", PROXY_AUTH.getFirst().getSecretArn(), "ENABLED",
                "SQL_SERVER_AUTHENTICATION", "SQL Server credentials");
        DbProxy sqlServerProxy = rdsService.createDbProxy(
                "sqlserver-proxy", "SQLSERVER", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), List.of(sqlServerAuth),
                1800, false, Map.of(), "us-east-1");
        assertEquals("ENABLED", sqlServerProxy.getAuth().getFirst().getIamAuth());
        assertTrue(sqlServerProxy.isIamAuth());

        DbProxyAuth sqlServerAuthDisabled = new DbProxyAuth(
                "SECRETS", PROXY_AUTH.getFirst().getSecretArn(), "DISABLED",
                "SQL_SERVER_AUTHENTICATION", "SQL Server credentials");
        DbProxy sqlServerToModify = rdsService.createDbProxy(
                "sqlserver-modify-proxy", "SQLSERVER", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), List.of(sqlServerAuthDisabled),
                1800, false, Map.of(), "us-east-1");
        assertFalse(sqlServerToModify.isIamAuth());
        DbProxy sqlServerModified = rdsService.modifyDbProxy(
                "sqlserver-modify-proxy", null, List.of(sqlServerAuth),
                null, null, null, null, null, null, "us-east-1");
        assertTrue(sqlServerModified.isIamAuth());

        DbProxy created = rdsService.createDbProxy(
                "modify-auth-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH,
                1800, false, Map.of(), "us-east-1");
        AwsException invalidModify = assertThrows(AwsException.class, () ->
                rdsService.modifyDbProxy(
                        created.getDbProxyName(), null, List.of(invalidEntries.getFirst()),
                        null, null, null, null, null, null, "us-east-1"));
        assertEquals("InvalidParameterValue", invalidModify.getErrorCode());
        assertEquals("SECRETS", rdsService.getDbProxy(
                created.getDbProxyName(), "us-east-1").getAuth().getFirst().getAuthScheme());
    }

    @Test
    void modifyDbProxyIsCopyOnWriteAndPreservesIdentity() {
        DbProxy created = rdsService.createDbProxy(
                "app-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of("sg-old"), PROXY_AUTH,
                1800, false, Map.of("owner", "old"), "us-east-1");
        Instant originalUpdatedAt = created.getUpdatedAt();

        DbProxy modified = rdsService.modifyDbProxy(
                "app-proxy", "NONE", PROXY_AUTH, false, 900, true,
                PROXY_ROLE_ARN, List.of("sg-new"), Map.of("owner", "new"), "us-east-1");

        assertEquals(created.getDbProxyArn(), modified.getDbProxyArn());
        assertEquals(created.getDbProxyResourceId(), modified.getDbProxyResourceId());
        assertEquals(created.getEndpoint(), modified.getEndpoint());
        assertEquals(created.getProxyPort(), modified.getProxyPort());
        assertEquals(created.getCreatedAt(), modified.getCreatedAt());
        assertFalse(modified.isRequireTls());
        assertEquals(900, modified.getIdleClientTimeout());
        assertTrue(modified.isDebugLogging());
        assertEquals(List.of("sg-new"), modified.getVpcSecurityGroupIds());
        assertEquals(Map.of("owner", "new"), modified.getTags());
        assertFalse(modified.getUpdatedAt().isBefore(originalUpdatedAt));

        Instant modifiedAt = modified.getUpdatedAt();
        DbProxy unchanged = rdsService.modifyDbProxy(
                "app-proxy", "NONE", PROXY_AUTH, false, 900, true,
                PROXY_ROLE_ARN, List.of("sg-new"), Map.of("owner", "new"), "us-east-1");
        assertSame(modified, unchanged);
        assertEquals(modifiedAt, unchanged.getUpdatedAt());
    }

    @Test
    void createDbProxyPreservesOriginalFailureWhenProxyCleanupFails() {
        InMemoryStorage<String, DbProxy> proxies = spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = spy(new InMemoryStorage<>());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());
        String proxyKey = "us-east-1::first-proxy";
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated target-group persistence failure");
        IllegalStateException cleanupFailure =
                new IllegalStateException("simulated proxy cleanup failure");
        doThrow(persistenceFailure).when(targetGroups).put(eq(proxyKey), any());
        doThrow(cleanupFailure).doCallRealMethod().when(proxies).delete(proxyKey);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.createDbProxy(
                        "first-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of()));

        assertSame(persistenceFailure, thrown);
        assertTrue(List.of(thrown.getSuppressed()).contains(cleanupFailure));
        verify(targetGroups).delete(proxyKey);
        DbProxy first = proxies.get(proxyKey).orElseThrow();
        DbProxy second = service.createDbProxy(
                "second-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertNotEquals(first.getProxyPort(), second.getProxyPort());

        service.deleteDbProxy("first-proxy", "us-east-1");
        DbProxy third = service.createDbProxy(
                "third-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertEquals(first.getProxyPort(), third.getProxyPort());
    }

    @Test
    void createDbProxyContinuesCleanupWhenTargetGroupCleanupFails() {
        InMemoryStorage<String, DbProxy> proxies = spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = spy(new InMemoryStorage<>());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());
        String proxyKey = "us-east-1::first-proxy";
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated post-mutation persistence failure");
        IllegalStateException cleanupFailure =
                new IllegalStateException("simulated post-mutation cleanup failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw persistenceFailure;
        }).doCallRealMethod().when(targetGroups).put(eq(proxyKey), any());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw cleanupFailure;
        }).doCallRealMethod().when(targetGroups).delete(proxyKey);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.createDbProxy(
                        "first-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of()));

        assertSame(persistenceFailure, thrown);
        assertTrue(List.of(thrown.getSuppressed()).contains(cleanupFailure));
        assertTrue(proxies.get(proxyKey).isEmpty());
        assertTrue(targetGroups.get(proxyKey).isEmpty());
        DbProxy replacement = service.createDbProxy(
                "replacement-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertEquals(5432, replacement.getProxyPort());
    }

    @Test
    void createDbProxyRestoresRetryOwnerWhenTargetGroupCleanupFailsBeforeMutation() {
        InMemoryStorage<String, DbProxy> proxies = spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = spy(new InMemoryStorage<>());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());
        String proxyKey = "us-east-1::first-proxy";
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated post-mutation persistence failure");
        IllegalStateException cleanupFailure =
                new IllegalStateException("simulated pre-mutation cleanup failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw persistenceFailure;
        }).doCallRealMethod().when(targetGroups).put(eq(proxyKey), any());
        doThrow(cleanupFailure).doCallRealMethod().when(targetGroups).delete(proxyKey);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.createDbProxy(
                        "first-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of()));

        assertSame(persistenceFailure, thrown);
        assertTrue(List.of(thrown.getSuppressed()).contains(cleanupFailure));
        DbProxy first = proxies.get(proxyKey).orElseThrow();
        assertTrue(targetGroups.get(proxyKey).isPresent());
        DbProxy second = service.createDbProxy(
                "second-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertNotEquals(first.getProxyPort(), second.getProxyPort());

        service.deleteDbProxy("first-proxy", "us-east-1");
        DbProxy third = service.createDbProxy(
                "third-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertEquals(first.getProxyPort(), third.getProxyPort());
    }

    @Test
    void modifyDbProxyPersistenceFailureLeavesStoredStateUntouched() {
        InMemoryStorage<String, DbProxy> proxies = spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "abc", 5432);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "abc");
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", targetGroup);
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated post-mutation persistence failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw persistenceFailure;
        }).doCallRealMethod().when(proxies).put(eq("us-east-1::app-proxy"), any());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.modifyDbProxy(
                "app-proxy", null, null, null, null, true,
                null, null, null, "us-east-1"));

        assertSame(persistenceFailure, thrown);
        assertFalse(proxy.isDebugLogging());
        assertSame(proxy, service.getDbProxy("app-proxy", "us-east-1"));
    }

    @Test
    void modifyDbProxyRestartsOriginalRelayWhenInitialStopPartiallySucceeds() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "relay", 5432);
        proxy.setDefaultAuthScheme("NONE");
        proxy.setIamAuth(false);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "relay");
        targetGroup.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "db1",
                "arn:aws:rds:us-east-1:123456789012:db:db1", "localhost", 15432)));
        DbInstance instance = persistedInstance("db1", "123456789012", "secret", 15432);
        instance.setContainerHost("localhost");
        instance.setContainerPort(15432);
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", targetGroup);
        instances.put("db1", instance);
        AtomicBoolean relayRunning = new AtomicBoolean(true);
        IllegalStateException stopFailure =
                new IllegalStateException("simulated post-stop failure");
        doAnswer(invocation -> {
            relayRunning.set(false);
            throw stopFailure;
        }).when(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
        java.util.ArrayList<Boolean> startedModes = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            startedModes.add(invocation.getArgument(2));
            relayRunning.set(true);
            return null;
        }).when(proxyManager).startProxy(any(), any(), anyBoolean(), anyInt(), any(),
                anyInt(), any(), any(), any(), any(), any());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups, instances, new InMemoryStorage<>());
        DbProxyAuth requiredAuth = new DbProxyAuth(
                "SECRETS", PROXY_AUTH.getFirst().getSecretArn(), "REQUIRED", null, null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.modifyDbProxy("app-proxy", null, List.of(requiredAuth),
                        null, null, null, null, null, null, "us-east-1"));

        assertSame(stopFailure, thrown);
        assertTrue(relayRunning.get());
        assertEquals(List.of(false), startedModes);
        assertSame(proxy, proxies.get("us-east-1::app-proxy").orElseThrow());
        assertFalse(proxy.isIamAuth());
    }

    @Test
    void modifyDbProxyRestoresDurableStateAndRelayAfterPostMutationFailure() {
        InMemoryStorage<String, DbProxy> proxies = spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "relay", 5432);
        proxy.setDefaultAuthScheme("NONE");
        proxy.setIamAuth(false);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "relay");
        targetGroup.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "db1",
                "arn:aws:rds:us-east-1:123456789012:db:db1", "localhost", 15432)));
        DbInstance instance = persistedInstance("db1", "123456789012", "secret", 15432);
        instance.setContainerHost("localhost");
        instance.setContainerPort(15432);
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", targetGroup);
        instances.put("db1", instance);
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated post-mutation persistence failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw persistenceFailure;
        }).doCallRealMethod().when(proxies).put(eq("us-east-1::app-proxy"), any());
        AtomicBoolean relayRunning = new AtomicBoolean(true);
        doAnswer(invocation -> {
            relayRunning.set(false);
            return null;
        }).when(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
        java.util.ArrayList<Boolean> startedModes = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            startedModes.add(invocation.getArgument(2));
            relayRunning.set(true);
            return null;
        }).when(proxyManager).startProxy(any(), any(), anyBoolean(), anyInt(), any(),
                anyInt(), any(), any(), any(), any(), any());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups, instances, new InMemoryStorage<>());
        DbProxyAuth requiredAuth = new DbProxyAuth(
                "SECRETS", PROXY_AUTH.getFirst().getSecretArn(), "REQUIRED", null, null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.modifyDbProxy("app-proxy", null, List.of(requiredAuth),
                        null, null, null, null, null, null, "us-east-1"));

        assertSame(persistenceFailure, thrown);
        assertTrue(relayRunning.get());
        assertEquals(List.of(true, false), startedModes);
        DbProxy restored = proxies.get("us-east-1::app-proxy").orElseThrow();
        assertSame(proxy, restored);
        assertFalse(restored.isIamAuth());
        assertEquals("DISABLED", restored.getAuth().getFirst().getIamAuth());
    }

    @Test
    void dbProxyIdentityAndTagsAreRegionScoped() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        RdsService service = new RdsService(containerManager, proxyManager, ec2Service,
                regionResolver, config, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                null, null, proxies, targetGroups);

        DbProxy east = service.createDbProxy(
                "shared-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, 1800, false,
                Map.of("region", "east"), "us-east-1");
        DbProxy west = service.createDbProxy(
                "shared-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, 1800, false,
                Map.of("region", "west"), "us-west-2");

        assertTrue(proxies.get("us-east-1::shared-proxy").isPresent());
        assertTrue(proxies.get("us-west-2::shared-proxy").isPresent());
        assertTrue(targetGroups.get("us-east-1::shared-proxy").isPresent());
        assertTrue(targetGroups.get("us-west-2::shared-proxy").isPresent());
        assertEquals(east.getDbProxyArn(), service.getDbProxy("shared-proxy", "us-east-1").getDbProxyArn());
        assertEquals(west.getDbProxyArn(), service.getDbProxy("shared-proxy", "us-west-2").getDbProxyArn());
        assertEquals(Map.of("region", "east"), service.listTagsForResource(east.getDbProxyArn(), "us-east-1"));
        assertEquals(Map.of("region", "west"), service.listTagsForResource(west.getDbProxyArn(), "us-west-2"));

        AwsException wrongRegion = assertThrows(AwsException.class, () ->
                service.addTagsToResource(west.getDbProxyArn(), Map.of("bad", "tag"), "us-east-1"));
        assertEquals("InvalidParameterValue", wrongRegion.getErrorCode());
        String wrongAccountArn = east.getDbProxyArn().replace("123456789012", "999999999999");
        AwsException wrongAccount = assertThrows(AwsException.class, () ->
                service.addTagsToResource(wrongAccountArn, Map.of("bad", "tag"), "us-east-1"));
        assertEquals("InvalidParameterValue", wrongAccount.getErrorCode());

        service.deleteDbProxy("shared-proxy", "us-west-2");
        assertEquals(east.getDbProxyArn(), service.getDbProxy("shared-proxy", "us-east-1").getDbProxyArn());
        assertThrows(AwsException.class, () -> service.getDbProxy("shared-proxy", "us-west-2"));
    }

    @Test
    void dbProxyTagsRoundTripByArn() {
        DbProxy proxy = rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false,
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of("owner", "platform"));

        assertEquals(Map.of("owner", "platform"), rdsService.listTagsForResource(proxy.getDbProxyArn()));
        rdsService.addTagsToResource(proxy.getDbProxyArn(), Map.of("env", "test"));
        assertEquals(Map.of("owner", "platform", "env", "test"),
                rdsService.listTagsForResource(proxy.getDbProxyArn()));
        rdsService.removeTagsFromResource(proxy.getDbProxyArn(), List.of("owner"));
        assertEquals(Map.of("env", "test"), rdsService.listTagsForResource(proxy.getDbProxyArn()));
    }

    @Test
    void targetGroupConfigurationValidatesBeforeMutatingStoredState() {
        rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false,
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());

        AwsException invalid = assertThrows(AwsException.class, () ->
                rdsService.configureDbProxyTargetGroup("app-proxy", "default", 80, 101));

        assertEquals("InvalidParameterValue", invalid.getErrorCode());
        DbProxyTargetGroup targetGroup =
                rdsService.describeDbProxyTargetGroups("app-proxy").iterator().next();
        assertEquals(100, targetGroup.getMaxConnectionsPercent());
        assertEquals(50, targetGroup.getMaxIdleConnectionsPercent());

        AwsException missingMaxConnections = assertThrows(AwsException.class, () ->
                rdsService.configureDbProxyTargetGroup("app-proxy", "default", null, 0));
        assertEquals("InvalidParameterValue", missingMaxConnections.getErrorCode());

        DbProxyTargetGroup configured =
                rdsService.configureDbProxyTargetGroup("app-proxy", "default", 80, null);
        assertEquals(80, configured.getMaxConnectionsPercent());
        assertEquals(40, configured.getMaxIdleConnectionsPercent());
    }

    @Test
    void targetGroupConfigurationRestoresStateAfterPostMutationPersistenceFailure() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = spy(new InMemoryStorage<>());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());
        service.createDbProxy(
                "app-proxy", "MYSQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        DbProxyTargetGroup original = targetGroups.get(
                "us-east-1::app-proxy").orElseThrow();
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated post-mutation persistence failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw persistenceFailure;
        }).doCallRealMethod().when(targetGroups).put(eq("us-east-1::app-proxy"), any());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.configureDbProxyTargetGroup(
                        "app-proxy", "default", 80, 20, 45,
                        "SET application_name = 'floci'",
                        List.of("EXCLUDE_VARIABLE_SETS"), "us-east-1"));

        assertSame(persistenceFailure, thrown);
        assertProxyTargetGroupState(original,
                targetGroups.get("us-east-1::app-proxy").orElseThrow());
    }

    @Test
    void targetGroupReconciliationIsCompleteAndIdempotent() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-mysql", "8.0.36",
                "admin", "secret", "app", false, null);
        rdsService.createDbProxy("mysql-proxy", "MYSQL", true, false,
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());

        DbProxyTargetGroup reconciled = rdsService.reconcileDbProxyTargetGroup(
                "mysql-proxy", "default", List.of("cluster1"), List.of(),
                85, 35, 45, "SET sql_mode='ANSI'", List.of("EXCLUDE_VARIABLE_SETS"),
                "us-east-1");
        String targetGroupArn = reconciled.getTargetGroupArn();
        Instant createdAt = reconciled.getCreatedAt();
        Instant updatedAt = reconciled.getUpdatedAt();

        assertEquals(1, reconciled.getTargets().size());
        assertEquals(85, reconciled.getMaxConnectionsPercent());
        assertEquals(35, reconciled.getMaxIdleConnectionsPercent());
        assertEquals(45, reconciled.getConnectionBorrowTimeout());
        assertEquals("SET sql_mode='ANSI'", reconciled.getInitQuery());
        assertEquals(List.of("EXCLUDE_VARIABLE_SETS"), reconciled.getSessionPinningFilters());

        DbProxyTargetGroup retry = rdsService.reconcileDbProxyTargetGroup(
                "mysql-proxy", "default", List.of("cluster1"), List.of(),
                85, 35, 45, "SET sql_mode='ANSI'", List.of("EXCLUDE_VARIABLE_SETS"),
                "us-east-1");

        assertEquals(targetGroupArn, retry.getTargetGroupArn());
        assertEquals(createdAt, retry.getCreatedAt());
        assertEquals(updatedAt, retry.getUpdatedAt());
        assertEquals(1, retry.getTargets().size());
    }

    @Test
    void targetGroupReconciliationValidatesBeforeMutation() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null);
        rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false,
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        DbProxyTargetGroup before = rdsService.describeDbProxyTargetGroups("app-proxy")
                .iterator().next();

        AwsException invalid = assertThrows(AwsException.class, () ->
                rdsService.reconcileDbProxyTargetGroup(
                        "app-proxy", "default", List.of("cluster1"), List.of(),
                        80, 40, 120, null, List.of("EXCLUDE_VARIABLE_SETS"), "us-east-1"));

        assertEquals("InvalidParameterValue", invalid.getErrorCode());
        DbProxyTargetGroup after = rdsService.describeDbProxyTargetGroups("app-proxy")
                .iterator().next();
        assertEquals(before.getTargetGroupArn(), after.getTargetGroupArn());
        assertEquals(before.getUpdatedAt(), after.getUpdatedAt());
        assertTrue(after.getTargets().isEmpty());
    }

    @Test
    void proxyTargetRegistrationRejectsCrossRegionDatabase() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null,
                null, null, false, "us-east-1");
        rdsService.createDbProxy(
                "west-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, 1800, false, Map.of(), "us-west-2");

        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.registerDbProxyTargets(
                        "west-proxy", "default", List.of("cluster1"), List.of(),
                        0, 0, "us-west-2"));

        assertEquals("DBClusterNotFoundFault", exception.getErrorCode());
        assertTrue(rdsService.describeDbProxyTargets(
                "west-proxy", "default", "us-west-2").isEmpty());
    }

    @Test
    void clearingTargetGroupPreservesIdentityResetsDefaultsAndAllowsReregistration() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null);
        rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false,
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        DbProxyTargetGroup registered = rdsService.registerDbProxyTargets("app-proxy", "default",
                List.of("cluster1"), List.of(), 80, 20);

        rdsService.clearDbProxyTargetGroupByArn(registered.getTargetGroupArn());

        DbProxyTargetGroup cleared = rdsService.describeDbProxyTargetGroups("app-proxy")
                .iterator().next();
        assertEquals(registered.getTargetGroupArn(), cleared.getTargetGroupArn());
        assertTrue(cleared.isDefaultTargetGroup());
        assertTrue(cleared.getTargets().isEmpty());
        assertEquals(100, cleared.getMaxConnectionsPercent());
        assertEquals(50, cleared.getMaxIdleConnectionsPercent());

        DbProxyTargetGroup reregistered = rdsService.registerDbProxyTargets("app-proxy", "default",
                List.of("cluster1"), List.of(), 0, 0);
        assertEquals(1, reregistered.getTargets().size());
    }

    @Test
    void clearingTargetGroupRestoresConfigurationAndRelayAfterPersistenceFailure() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "clear", 5432);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "clear");
        targetGroup.setMaxConnectionsPercent(73);
        targetGroup.setMaxIdleConnectionsPercent(29);
        targetGroup.setConnectionBorrowTimeout(45);
        targetGroup.setInitQuery("SET application_name = 'floci'");
        targetGroup.setSessionPinningFilters(List.of("EXCLUDE_VARIABLE_SETS"));
        targetGroup.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "db1",
                "arn:aws:rds:us-east-1:123456789012:db:db1", "localhost", 15432)));
        DbInstance instance = persistedInstance("db1", "123456789012", "secret", 15432);
        instance.setContainerHost("localhost");
        instance.setContainerPort(15432);
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", targetGroup);
        instances.put("db1", instance);
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated post-mutation persistence failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw persistenceFailure;
        }).doCallRealMethod().when(targetGroups).put(eq("us-east-1::app-proxy"), any());
        AtomicBoolean relayRunning = new AtomicBoolean(true);
        doAnswer(invocation -> {
            relayRunning.set(false);
            return null;
        }).when(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
        doAnswer(invocation -> {
            relayRunning.set(true);
            return null;
        }).when(proxyManager).startProxy(any(), any(), anyBoolean(), anyInt(), any(),
                anyInt(), any(), any(), any(), any(), any());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups, instances, new InMemoryStorage<>());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.clearDbProxyTargetGroupByArn(
                        targetGroup.getTargetGroupArn(), "us-east-1"));

        assertSame(persistenceFailure, thrown);
        assertTrue(relayRunning.get());
        assertProxyTargetGroupState(targetGroup,
                targetGroups.get("us-east-1::app-proxy").orElseThrow());

        service.clearDbProxyTargetGroupByArn(
                targetGroup.getTargetGroupArn(), "us-east-1");
        DbProxyTargetGroup cleared = targetGroups.get(
                "us-east-1::app-proxy").orElseThrow();
        assertFalse(relayRunning.get());
        assertTrue(cleared.getTargets().isEmpty());
        assertEquals(100, cleared.getMaxConnectionsPercent());
        assertEquals(50, cleared.getMaxIdleConnectionsPercent());
        assertEquals(120, cleared.getConnectionBorrowTimeout());
        assertNull(cleared.getInitQuery());
        assertTrue(cleared.getSessionPinningFilters().isEmpty());
    }

    @Test
    void sqlServerTargetGroupUsesEngineSpecificPoolDefaults() {
        rdsService.createDbProxy("sqlserver-proxy", "SQLSERVER", true, false,
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        DbProxyTargetGroup targetGroup = rdsService.describeDbProxyTargetGroups("sqlserver-proxy")
                .iterator().next();

        assertEquals(10, targetGroup.getMaxConnectionsPercent());
        assertEquals(5, targetGroup.getMaxIdleConnectionsPercent());

        rdsService.configureDbProxyTargetGroup("sqlserver-proxy", "default", 80, 20);
        rdsService.clearDbProxyTargetGroupByArn(targetGroup.getTargetGroupArn());

        DbProxyTargetGroup cleared = rdsService.describeDbProxyTargetGroups("sqlserver-proxy")
                .iterator().next();
        assertEquals(10, cleared.getMaxConnectionsPercent());
        assertEquals(5, cleared.getMaxIdleConnectionsPercent());
    }

    @Test
    void registerDbProxyTargetRejectsDuplicateAndSupportsDeregistration() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null);
        rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        rdsService.registerDbProxyTargets("app-proxy", "default",
                List.of("cluster1"), List.of(), 0, 0);

        AwsException duplicate = assertThrows(AwsException.class, () ->
                rdsService.registerDbProxyTargets("app-proxy", "default",
                        List.of("cluster1"), List.of(), 0, 0));
        assertEquals("DBProxyTargetAlreadyRegisteredFault", duplicate.getErrorCode());

        AwsException referenced = assertThrows(AwsException.class, () ->
                rdsService.deleteDbCluster("cluster1"));
        assertEquals("InvalidDBClusterStateFault", referenced.getErrorCode());

        rdsService.deregisterDbProxyTargets("app-proxy", "default", List.of("cluster1"), List.of());
        assertTrue(rdsService.describeDbProxyTargets("app-proxy", "default").isEmpty());
        rdsService.deleteDbCluster("cluster1");

        AwsException missing = assertThrows(AwsException.class, () ->
                rdsService.deregisterDbProxyTargets("app-proxy", "default",
                        List.of("cluster1"), List.of()));
        assertEquals("DBProxyTargetNotFoundFault", missing.getErrorCode());
    }

    @Test
    void deregistrationRestoresTargetAndRelayAfterStopAndPersistenceFailures() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "abc", 5432);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "abc");
        targetGroup.setMaxConnectionsPercent(77);
        targetGroup.setMaxIdleConnectionsPercent(31);
        targetGroup.setConnectionBorrowTimeout(45);
        targetGroup.setInitQuery("SET application_name = 'floci'");
        targetGroup.setSessionPinningFilters(List.of("EXCLUDE_VARIABLE_SETS"));
        targetGroup.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "db1",
                "arn:aws:rds:us-east-1:123456789012:db:db1", "localhost", 15432)));
        DbInstance instance = persistedInstance("db1", "123456789012", "secret", 15432);
        instance.setContainerHost("localhost");
        instance.setContainerPort(15432);
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", targetGroup);
        instances.put("db1", instance);
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated post-mutation persistence failure");
        doCallRealMethod().doAnswer(invocation -> {
            invocation.callRealMethod();
            throw persistenceFailure;
        }).doCallRealMethod().when(targetGroups).put(eq("us-east-1::app-proxy"), any());
        AtomicBoolean relayRunning = new AtomicBoolean(true);
        IllegalStateException stopFailure =
                new IllegalStateException("simulated post-stop failure");
        doAnswer(invocation -> {
            relayRunning.set(false);
            throw stopFailure;
        }).doAnswer(invocation -> {
            relayRunning.set(false);
            return null;
        }).when(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
        doAnswer(invocation -> {
            relayRunning.set(true);
            return null;
        }).when(proxyManager).startProxy(any(), any(), anyBoolean(), anyInt(), any(),
                anyInt(), any(), any(), any(), any(), any());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups, instances, new InMemoryStorage<>());

        IllegalStateException stopThrown = assertThrows(IllegalStateException.class, () ->
                service.deregisterDbProxyTargets(
                        "app-proxy", "default", List.of(), List.of("db1")));

        assertSame(stopFailure, stopThrown);
        assertTrue(relayRunning.get());
        assertProxyTargetGroupState(targetGroup,
                targetGroups.get("us-east-1::app-proxy").orElseThrow());

        IllegalStateException persistenceThrown = assertThrows(IllegalStateException.class, () ->
                service.deregisterDbProxyTargets(
                        "app-proxy", "default", List.of(), List.of("db1")));

        assertSame(persistenceFailure, persistenceThrown);
        assertTrue(relayRunning.get());
        assertProxyTargetGroupState(targetGroup,
                targetGroups.get("us-east-1::app-proxy").orElseThrow());

        service.deregisterDbProxyTargets(
                "app-proxy", "default", List.of(), List.of("db1"));
        assertFalse(relayRunning.get());
        assertTrue(targetGroups.get("us-east-1::app-proxy").orElseThrow()
                .getTargets().isEmpty());
    }

    @Test
    void registrationRollsBackMutateThenThrowProxyStatusPersistence() {
        InMemoryStorage<String, DbProxy> proxies =
                spy(new InMemoryStorage<String, DbProxy>());
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "mysql-proxy", "us-east-1", "123456789012", "current", 3306);
        proxy.setEngineFamily("MYSQL");
        proxy.setStatus("insufficient-resource-limits");
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "mysql-proxy", "us-east-1", "123456789012", "current");
        targetGroup.setCreatedAt(proxy.getCreatedAt());
        targetGroup.setUpdatedAt(proxy.getCreatedAt());
        DbInstance instance = persistedInstance(
                "mysql-db", "123456789012", "secret", 7000);
        instance.setEngine(DatabaseEngine.MYSQL);
        instance.setContainerHost("localhost");
        instance.setContainerPort(3306);
        proxies.put("us-east-1::mysql-proxy", proxy);
        targetGroups.put("us-east-1::mysql-proxy", targetGroup);
        instances.put("mysql-db", instance);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("simulated post-mutation persistence failure");
        }).doCallRealMethod().when(proxies).put(
                eq("us-east-1::mysql-proxy"), any());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                instances, new InMemoryStorage<>());

        assertThrows(IllegalStateException.class, () -> service.registerDbProxyTargets(
                "mysql-proxy", "default", List.of(), List.of("mysql-db"), 0, 0));

        assertEquals("insufficient-resource-limits", proxies.get(
                "us-east-1::mysql-proxy").orElseThrow().getStatus());
        assertTrue(targetGroups.get("us-east-1::mysql-proxy").orElseThrow()
                .getTargets().isEmpty());
        verify(proxyManager).startProxy(
                eq("db-proxy:" + proxy.getDbProxyArn()), eq(DatabaseEngine.MYSQL),
                anyBoolean(), eq(3306), eq("localhost"), eq(3306), any(),
                eq("admin"), eq("secret"), eq("app"), any());
        verify(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
    }

    @Test
    void registrationStopsRelayWhenStartupPartiallySucceeds() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "mysql-proxy", "us-east-1", "123456789012", "current", 3306);
        proxy.setEngineFamily("MYSQL");
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "mysql-proxy", "us-east-1", "123456789012", "current");
        DbInstance instance = persistedInstance(
                "mysql-db", "123456789012", "secret", 3306);
        instance.setEngine(DatabaseEngine.MYSQL);
        instance.setContainerHost("localhost");
        instance.setContainerPort(3306);
        proxies.put("us-east-1::mysql-proxy", proxy);
        targetGroups.put("us-east-1::mysql-proxy", targetGroup);
        instances.put("mysql-db", instance);
        AtomicBoolean relayRunning = new AtomicBoolean(false);
        IllegalStateException startupFailure =
                new IllegalStateException("simulated post-start failure");
        doAnswer(invocation -> {
            relayRunning.set(true);
            throw startupFailure;
        }).when(proxyManager).startProxy(any(), any(), anyBoolean(), anyInt(), any(),
                anyInt(), any(), any(), any(), any(), any());
        doAnswer(invocation -> {
            relayRunning.set(false);
            return null;
        }).when(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups, instances, new InMemoryStorage<>());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.registerDbProxyTargets(
                        "mysql-proxy", "default", List.of(), List.of("mysql-db"), 0, 0));

        assertSame(startupFailure, thrown);
        assertFalse(relayRunning.get());
        assertSame(proxy, proxies.get("us-east-1::mysql-proxy").orElseThrow());
        assertTrue(targetGroups.get("us-east-1::mysql-proxy").orElseThrow()
                .getTargets().isEmpty());
    }

    @Test
    void deletingLegacyDbProxyAlsoRemovesLegacyTargetGroup() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy("app-proxy", "us-east-1", "123456789012", "old", 5432);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "old");
        proxies.put("app-proxy", proxy);
        targetGroups.put("app-proxy", targetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.deleteDbProxy("app-proxy", "us-east-1");

        assertTrue(proxies.get("app-proxy").isEmpty());
        assertTrue(proxies.get("us-east-1::app-proxy").isEmpty());
        assertTrue(targetGroups.get("app-proxy").isEmpty());
        assertTrue(targetGroups.get("us-east-1::app-proxy").isEmpty());
    }

    @Test
    void staleProxyAndTargetGroupArnsCannotOverwriteRecreatedResources() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy current = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "new", 5432);
        current.setTags(Map.of("generation", "new"));
        DbProxy stale = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "old", 5432);
        DbProxyTargetGroup currentTargetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "new");
        DbProxyTargetGroup staleTargetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "old");
        proxies.put("us-east-1::app-proxy", current);
        proxies.put("app-proxy", stale);
        targetGroups.put("us-east-1::app-proxy", currentTargetGroup);
        targetGroups.put("app-proxy", staleTargetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        AwsException staleProxyArn = assertThrows(AwsException.class, () ->
                service.addTagsToResource(stale.getDbProxyArn(), Map.of("bad", "tag"), "us-east-1"));
        AwsException staleTargetGroupArn = assertThrows(AwsException.class, () ->
                service.clearDbProxyTargetGroupByArn(
                        staleTargetGroup.getTargetGroupArn(), "us-east-1"));

        assertEquals("DBProxyNotFoundFault", staleProxyArn.getErrorCode());
        assertEquals("DBProxyTargetGroupNotFoundFault", staleTargetGroupArn.getErrorCode());
        assertEquals(current.getDbProxyArn(),
                proxies.get("us-east-1::app-proxy").orElseThrow().getDbProxyArn());
        assertEquals(Map.of("generation", "new"),
                proxies.get("us-east-1::app-proxy").orElseThrow().getTags());
        assertEquals(currentTargetGroup.getTargetGroupArn(),
                targetGroups.get("us-east-1::app-proxy").orElseThrow().getTargetGroupArn());
    }

    @Test
    void rawLegacyProxyCannotBeClaimedByAnotherAccount() {
        String foreignAccount = "222222222222";
        String currentAccount = "333333333333";
        RegionResolver otherRegionResolver = new RegionResolver("us-east-1", currentAccount);
        EmulatorConfig otherConfig = mock(EmulatorConfig.class);
        when(otherConfig.defaultAccountId()).thenReturn(currentAccount);
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        rawProxies.put("app-proxy", persistedProxy(
                "app-proxy", "us-east-1", foreignAccount, "foreign", 5432));
        rawProxies.put(currentAccount + "/us-east-1::corrupt-proxy", persistedProxy(
                "corrupt-proxy", "us-east-1", foreignAccount, "corrupt", 5433));
        AccountAwareStorageBackend<DbProxy> proxies =
                new AccountAwareStorageBackend<>(rawProxies, null, currentAccount);
        AccountAwareStorageBackend<DbProxyTargetGroup> targetGroups =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, currentAccount);
        RdsService service = proxyStoreService(
                otherRegionResolver, otherConfig, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        AwsException exception = assertThrows(AwsException.class, () ->
                service.getDbProxy("app-proxy", "us-east-1"));

        assertEquals("DBProxyNotFoundFault", exception.getErrorCode());
        assertTrue(service.listDbProxies(null, "us-east-1").isEmpty());
        assertTrue(rawProxies.get("app-proxy").isPresent());
        assertTrue(rawProxies.get(currentAccount + "/app-proxy").isEmpty());
    }

    @Test
    void malformedPersistedProxyArnIsSkippedWithoutMigration() {
        when(config.services().rds().mock()).thenReturn(true);
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        DbProxy malformed = persistedProxy(
                "malformed-proxy", "us-east-1", "123456789012", "bad", 5432);
        malformed.setDbProxyArn("not-an-arn");
        proxies.put("malformed-proxy", malformed);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        assertTrue(service.listDbProxies(null, "us-east-1").isEmpty());
        AwsException missing = assertThrows(AwsException.class, () ->
                service.getDbProxy("malformed-proxy", "us-east-1"));
        assertEquals("DBProxyNotFoundFault", missing.getErrorCode());
        assertTrue(proxies.get("malformed-proxy").isPresent());
        assertTrue(proxies.get("us-east-1::malformed-proxy").isEmpty());
    }

    @Test
    void corruptCanonicalProxyAndTargetGroupFailClosedWithoutBeingOverwritten() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy wrongName = persistedProxy(
                "other-proxy", "us-east-1", "123456789012", "other", 5432);
        proxies.put("us-east-1::expected-proxy", wrongName);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        AwsException missing = assertThrows(AwsException.class, () ->
                service.getDbProxy("expected-proxy", "us-east-1"));
        assertEquals("DBProxyNotFoundFault", missing.getErrorCode());
        AwsException occupied = assertThrows(AwsException.class, () ->
                service.createDbProxy(
                        "expected-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH,
                        1800, false, Map.of(), "us-east-1"));
        assertEquals("DBProxyAlreadyExistsFault", occupied.getErrorCode());
        assertSame(wrongName, proxies.get("us-east-1::expected-proxy").orElseThrow());

        DbProxy current = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "current", 5433);
        DbProxyTargetGroup stale = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "stale");
        proxies.put("us-east-1::app-proxy", current);
        targetGroups.put("us-east-1::app-proxy", stale);

        AwsException staleGroup = assertThrows(AwsException.class, () ->
                service.describeDbProxyTargets("app-proxy", "default", "us-east-1"));
        assertEquals("DBProxyTargetGroupNotFoundFault", staleGroup.getErrorCode());
        assertSame(stale, targetGroups.get("us-east-1::app-proxy").orElseThrow());
    }

    @Test
    void proxyRestoreDoesNotReplaceCorruptCanonicalTargetGroupWithLegacyState() {
        when(config.services().rds().mock()).thenReturn(true);
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "current", 5432);
        DbProxyTargetGroup staleCanonical = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "stale");
        DbProxyTargetGroup recoverableLegacy = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "current");
        recoverableLegacy.setCreatedAt(proxy.getCreatedAt());
        recoverableLegacy.setUpdatedAt(proxy.getCreatedAt());
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", staleCanonical);
        targetGroups.put("app-proxy", recoverableLegacy);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        assertSame(staleCanonical,
                targetGroups.get("us-east-1::app-proxy").orElseThrow());
        assertSame(recoverableLegacy, targetGroups.get("app-proxy").orElseThrow());
        assertEquals("insufficient-resource-limits",
                service.getDbProxy("app-proxy", "us-east-1").getStatus());
    }

    @Test
    void proxyRestoreRejectsAccountScopedKeyWhoseModelHasAnotherName() {
        when(config.services().rds().mock()).thenReturn(true);
        String accountId = "123456789012";
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> rawTargetGroups = new InMemoryStorage<>();
        DbProxy wrongName = persistedProxy(
                "other-proxy", "us-east-1", accountId, "other", 5432);
        String corruptKey = accountId + "/us-east-1::expected-proxy";
        rawProxies.put(corruptKey, wrongName);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(rawProxies, null, accountId),
                new AccountAwareStorageBackend<>(rawTargetGroups, null, accountId),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        assertSame(wrongName, rawProxies.get(corruptKey).orElseThrow());
        assertTrue(rawProxies.get(accountId + "/us-east-1::other-proxy").isEmpty());
        assertTrue(rawTargetGroups.get(accountId + "/us-east-1::other-proxy").isEmpty());
        assertTrue(service.listDbProxies(null, "us-east-1").isEmpty());
    }

    @Test
    void proxyRestorePrefersRawRegionalCanonicalOverAccountNameLegacy() {
        when(config.services().rds().mock()).thenReturn(true);
        String accountId = "123456789012";
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> rawTargetGroups = new InMemoryStorage<>();
        DbProxy regional = persistedProxy(
                "app-proxy", "us-east-1", accountId, "shared", 5432);
        regional.setTags(Map.of("source", "regional"));
        DbProxy accountName = persistedProxy(
                "app-proxy", "us-east-1", accountId, "shared", 5432);
        accountName.setTags(Map.of("source", "account-name"));
        rawProxies.put("us-east-1::app-proxy", regional);
        rawProxies.put(accountId + "/app-proxy", accountName);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(rawProxies, null, accountId),
                new AccountAwareStorageBackend<>(rawTargetGroups, null, accountId),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        DbProxy canonical = rawProxies.get(
                accountId + "/us-east-1::app-proxy").orElseThrow();
        assertSame(regional, canonical);
        assertEquals(Map.of("source", "regional"), canonical.getTags());
        assertTrue(rawProxies.get("us-east-1::app-proxy").isEmpty());
        assertTrue(rawProxies.get(accountId + "/app-proxy").isEmpty());
    }

    @Test
    void proxyRestoreUsesRegionalLegacyGenerationDeterministically() {
        when(config.services().rds().mock()).thenReturn(true);
        String accountId = "123456789012";
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        DbProxy regional = persistedProxy(
                "app-proxy", "us-east-1", accountId, "regional", 5432);
        DbProxy accountName = persistedProxy(
                "app-proxy", "us-east-1", accountId, "account", 5433);
        rawProxies.put("us-east-1::app-proxy", regional);
        rawProxies.put(accountId + "/app-proxy", accountName);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(rawProxies, null, accountId),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, accountId),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        assertEquals("prx-regional", rawProxies.get(
                accountId + "/us-east-1::app-proxy").orElseThrow()
                .getDbProxyResourceId());
        assertTrue(rawProxies.get("us-east-1::app-proxy").isEmpty());
        assertSame(accountName, rawProxies.get(accountId + "/app-proxy").orElseThrow());
    }

    @Test
    void proxyRestoreRejectsInvalidPersistedIdentity() {
        when(config.services().rds().mock()).thenReturn(true);
        String accountId = "123456789012";
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> rawTargetGroups = new InMemoryStorage<>();
        DbProxy invalidName = persistedProxy(
                "bad/name", "us-east-1", accountId, "bad-name", 5432);
        DbProxy blankRegion = persistedProxy(
                "blank-region", "", accountId, "blank-region", 5433);
        invalidName.setDefaultAuthScheme(null);
        blankRegion.setDefaultAuthScheme(null);
        rawProxies.put(accountId + "/us-east-1::bad/name", invalidName);
        rawProxies.put(accountId + "/us-east-1::blank-region", blankRegion);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(rawProxies, null, accountId),
                new AccountAwareStorageBackend<>(rawTargetGroups, null, accountId),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        assertSame(invalidName, rawProxies.get(
                accountId + "/us-east-1::bad/name").orElseThrow());
        assertSame(blankRegion, rawProxies.get(
                accountId + "/us-east-1::blank-region").orElseThrow());
        assertNull(invalidName.getDefaultAuthScheme());
        assertNull(blankRegion.getDefaultAuthScheme());
        assertTrue(rawTargetGroups.keys().isEmpty());
    }

    @Test
    void proxyRestoreDoesNotClaimWrongKeyTargetGroup() {
        when(config.services().rds().mock()).thenReturn(true);
        String accountId = "123456789012";
        String otherAccount = "999999999999";
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> rawTargetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", accountId, "current", 5432);
        DbProxyTargetGroup wrongKey = persistedTargetGroup(
                "app-proxy", "us-east-1", accountId, "current");
        wrongKey.setCreatedAt(proxy.getCreatedAt());
        wrongKey.setUpdatedAt(proxy.getCreatedAt());
        wrongKey.setMaxConnectionsPercent(37);
        wrongKey.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "marker",
                "arn:aws:rds:us-east-1:" + accountId + ":db:marker",
                "localhost", 5432)));
        rawProxies.put(accountId + "/us-east-1::app-proxy", proxy);
        String wrongRawKey = otherAccount + "/us-east-1::wrong";
        rawTargetGroups.put(wrongRawKey, wrongKey);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(rawProxies, null, accountId),
                new AccountAwareStorageBackend<>(rawTargetGroups, null, accountId),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        DbProxyTargetGroup canonical = rawTargetGroups.get(
                accountId + "/us-east-1::app-proxy").orElseThrow();
        assertSame(wrongKey, rawTargetGroups.get(wrongRawKey).orElseThrow());
        assertNotSame(wrongKey, canonical);
        assertNotEquals(wrongKey.getTargetGroupArn(), canonical.getTargetGroupArn());
        assertTrue(canonical.getTargets().isEmpty());
        assertEquals(100, canonical.getMaxConnectionsPercent());
        assertEquals(37, wrongKey.getMaxConnectionsPercent());
    }

    @Test
    void restoreNormalizesLegacyTargetGroupWithoutLosingTargetsOrPoolConfiguration() {
        when(config.services().rds().mock()).thenReturn(true);
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "legacy", 3306);
        proxy.setEngineFamily("MYSQL");
        proxy.setCreatedAt(null);
        proxy.setUpdatedAt(null);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "legacy");
        targetGroup.setTargetGroupName(null);
        targetGroup.setDefaultTargetGroup(false);
        targetGroup.setCreatedAt(null);
        targetGroup.setUpdatedAt(null);
        targetGroup.setMaxConnectionsPercent(71);
        targetGroup.setMaxIdleConnectionsPercent(29);
        targetGroup.setConnectionBorrowTimeout(45);
        targetGroup.setInitQuery("SET application_name = 'floci'");
        targetGroup.setSessionPinningFilters(List.of("EXCLUDE_VARIABLE_SETS"));
        DbProxyTarget target = new DbProxyTarget(
                "RDS_INSTANCE", "db1",
                "arn:aws:rds:us-east-1:123456789012:db:db1", "localhost", 5432);
        targetGroup.setTargets(List.of(target));
        proxies.put("app-proxy", proxy);
        targetGroups.put("app-proxy", targetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        DbProxy restoredProxy = service.getDbProxy("app-proxy", "us-east-1");
        DbProxyTargetGroup restoredGroup = service.describeDbProxyTargetGroups(
                "app-proxy", "default", "us-east-1").iterator().next();
        assertNotNull(restoredProxy.getCreatedAt());
        assertEquals(restoredProxy.getCreatedAt(), restoredGroup.getCreatedAt());
        assertEquals("default", restoredGroup.getTargetGroupName());
        assertTrue(restoredGroup.isDefaultTargetGroup());
        assertEquals(List.of(target), restoredGroup.getTargets());
        assertEquals(71, restoredGroup.getMaxConnectionsPercent());
        assertEquals(29, restoredGroup.getMaxIdleConnectionsPercent());
        assertEquals(45, restoredGroup.getConnectionBorrowTimeout());
        assertEquals("SET application_name = 'floci'", restoredGroup.getInitQuery());
        assertEquals(List.of("EXCLUDE_VARIABLE_SETS"),
                restoredGroup.getSessionPinningFilters());
        assertTrue(proxies.get("app-proxy").isEmpty());
        assertTrue(targetGroups.get("app-proxy").isEmpty());
    }

    @Test
    void deleteDbProxySucceedsWhenItsCanonicalTargetGroupIsStale() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "current", 5432);
        DbProxyTargetGroup staleTargetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "stale");
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", staleTargetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        assertDoesNotThrow(() -> service.deleteDbProxy("app-proxy", "us-east-1"));

        assertTrue(proxies.get("us-east-1::app-proxy").isEmpty());
        assertTrue(targetGroups.get("us-east-1::app-proxy").isEmpty());
        verify(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
    }

    @Test
    void deleteDbProxySucceedsWhenTargetGroupIsMissing() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "current", 5432);
        proxies.put("us-east-1::app-proxy", proxy);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        assertDoesNotThrow(() -> service.deleteDbProxy("app-proxy", "us-east-1"));

        assertTrue(proxies.get("us-east-1::app-proxy").isEmpty());
        assertTrue(targetGroups.get("us-east-1::app-proxy").isEmpty());
        verify(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
    }

    @Test
    void failedDbProxyDeleteKeepsPortReservedUntilRetrySucceeds() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups =
                spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                instances, new InMemoryStorage<>());
        DbProxy first = service.createDbProxy(
                "first-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        DbProxyTargetGroup originalTargetGroup = targetGroups.get(
                "us-east-1::first-proxy").orElseThrow();
        originalTargetGroup.setMaxConnectionsPercent(79);
        originalTargetGroup.setMaxIdleConnectionsPercent(33);
        originalTargetGroup.setConnectionBorrowTimeout(45);
        originalTargetGroup.setInitQuery("SET application_name = 'floci'");
        originalTargetGroup.setSessionPinningFilters(List.of("EXCLUDE_VARIABLE_SETS"));
        originalTargetGroup.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "db1",
                "arn:aws:rds:us-east-1:123456789012:db:db1", "localhost", 15432)));
        DbInstance instance = persistedInstance("db1", "123456789012", "secret", 15432);
        instance.setContainerHost("localhost");
        instance.setContainerPort(15432);
        instances.put("db1", instance);
        AtomicBoolean relayRunning = new AtomicBoolean(true);
        doAnswer(invocation -> {
            relayRunning.set(false);
            return null;
        }).when(proxyManager).stopProxy("db-proxy:" + first.getDbProxyArn());
        doAnswer(invocation -> {
            relayRunning.set(true);
            return null;
        }).when(proxyManager).startProxy(any(), any(), anyBoolean(), anyInt(), any(),
                anyInt(), any(), any(), any(), any(), any());
        IllegalStateException deleteFailure =
                new IllegalStateException("simulated post-mutation target-group delete failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw deleteFailure;
        }).doCallRealMethod().when(targetGroups).delete("us-east-1::first-proxy");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.deleteDbProxy("first-proxy", "us-east-1"));
        assertSame(deleteFailure, thrown);
        assertEquals(first.getDbProxyArn(),
                service.getDbProxy("first-proxy", "us-east-1").getDbProxyArn());
        assertProxyTargetGroupState(originalTargetGroup,
                targetGroups.get("us-east-1::first-proxy").orElseThrow());
        assertTrue(relayRunning.get());

        DbProxy second = service.createDbProxy(
                "second-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertNotEquals(first.getProxyPort(), second.getProxyPort());

        assertDoesNotThrow(() -> service.deleteDbProxy("first-proxy", "us-east-1"));
        assertFalse(relayRunning.get());
        DbProxy third = service.createDbProxy(
                "third-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertEquals(first.getProxyPort(), third.getProxyPort());
    }

    @Test
    void mutateThenThrowDbProxyDeleteRestoresRetryOwnerAndKeepsPortReserved() {
        InMemoryStorage<String, DbProxy> proxies =
                spy(new InMemoryStorage<String, DbProxy>());
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                instances, new InMemoryStorage<>());
        DbProxy first = service.createDbProxy(
                "first-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        DbProxyTargetGroup originalTargetGroup = targetGroups.get(
                "us-east-1::first-proxy").orElseThrow();
        originalTargetGroup.setMaxConnectionsPercent(79);
        originalTargetGroup.setMaxIdleConnectionsPercent(33);
        originalTargetGroup.setConnectionBorrowTimeout(45);
        originalTargetGroup.setInitQuery("SET application_name = 'floci'");
        originalTargetGroup.setSessionPinningFilters(List.of("EXCLUDE_VARIABLE_SETS"));
        originalTargetGroup.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "db1",
                "arn:aws:rds:us-east-1:123456789012:db:db1", "localhost", 15432)));
        DbInstance instance = persistedInstance("db1", "123456789012", "secret", 15432);
        instance.setContainerHost("localhost");
        instance.setContainerPort(15432);
        instances.put("db1", instance);
        AtomicBoolean relayRunning = new AtomicBoolean(true);
        doAnswer(invocation -> {
            relayRunning.set(false);
            return null;
        }).when(proxyManager).stopProxy("db-proxy:" + first.getDbProxyArn());
        doAnswer(invocation -> {
            relayRunning.set(true);
            return null;
        }).when(proxyManager).startProxy(any(), any(), anyBoolean(), anyInt(), any(),
                anyInt(), any(), any(), any(), any(), any());
        IllegalStateException deleteFailure =
                new IllegalStateException("simulated post-mutation proxy delete failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw deleteFailure;
        }).doCallRealMethod().when(proxies).delete("us-east-1::first-proxy");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.deleteDbProxy("first-proxy", "us-east-1"));
        assertSame(deleteFailure, thrown);
        assertEquals(first.getDbProxyArn(), proxies.get(
                "us-east-1::first-proxy").orElseThrow().getDbProxyArn());
        assertProxyTargetGroupState(originalTargetGroup,
                targetGroups.get("us-east-1::first-proxy").orElseThrow());
        assertTrue(relayRunning.get());

        DbProxy second = service.createDbProxy(
                "second-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertNotEquals(first.getProxyPort(), second.getProxyPort());

        assertDoesNotThrow(() -> service.deleteDbProxy("first-proxy", "us-east-1"));
        assertFalse(relayRunning.get());
        DbProxy third = service.createDbProxy(
                "third-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertEquals(first.getProxyPort(), third.getProxyPort());
    }

    @Test
    void staleGenerationTargetGroupDoesNotBlockDbInstanceDeletion() {
        when(config.services().rds().mock()).thenReturn(true);
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                instances, new InMemoryStorage<>());
        DbInstance instance = service.createDbInstance(
                "db1", "postgres", "16.3", "admin", "secret", "app",
                "db.t3.micro", 20, false, null, null, null);
        DbProxy currentProxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "current", 5432);
        DbProxyTargetGroup staleTargetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "stale");
        staleTargetGroup.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "db1", instance.getDbInstanceArn(), "localhost", 5432)));
        proxies.put("us-east-1::app-proxy", currentProxy);
        targetGroups.put("us-east-1::app-proxy", staleTargetGroup);

        assertDoesNotThrow(() -> service.deleteDbInstance("db1", "us-east-1"));

        assertThrows(AwsException.class, () -> service.getDbInstance("db1", "us-east-1"));
        assertSame(staleTargetGroup,
                targetGroups.get("us-east-1::app-proxy").orElseThrow());
    }

    @Test
    void unsupportedPartitionAndMalformedTargetGroupResourceAreRejected() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "valid", 5432);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "valid");
        targetGroup.setCreatedAt(proxy.getCreatedAt());
        targetGroup.setUpdatedAt(proxy.getCreatedAt());
        targetGroup.setTargetGroupArn(
                "arn:bogus:rds:us-east-1:123456789012:target-group:prx-tg-valid:garbage");
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", targetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        AwsException missing = assertThrows(AwsException.class, () ->
                service.describeDbProxyTargets("app-proxy", "default", "us-east-1"));

        assertEquals("DBProxyTargetGroupNotFoundFault", missing.getErrorCode());
        assertSame(targetGroup, targetGroups.get("us-east-1::app-proxy").orElseThrow());
    }

    @Test
    void guardedLegacyRdsLookupsEnforceAccountOwnership() {
        String foreignAccount = "222222222222";
        String currentAccount = "333333333333";
        RegionResolver currentResolver = new RegionResolver("us-east-1", currentAccount);
        EmulatorConfig currentConfig = mock(EmulatorConfig.class);
        when(currentConfig.defaultAccountId()).thenReturn(currentAccount);

        InMemoryStorage<String, DbCluster> rawForeignClusters = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> rawForeignInstances = new InMemoryStorage<>();
        rawForeignClusters.put("cluster1", persistedCluster(foreignAccount, "secret", 7000));
        rawForeignInstances.put("instance1", persistedInstance(
                "instance1", foreignAccount, "secret", 7001));
        rawForeignClusters.put(currentAccount + "/scoped-cluster",
                persistedCluster(foreignAccount, "secret", 7002));
        rawForeignInstances.put(currentAccount + "/scoped-instance",
                persistedInstance("scoped-instance", foreignAccount, "secret", 7003));
        RdsService foreignService = proxyStoreService(
                currentResolver, currentConfig, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(rawForeignInstances, null, currentAccount),
                new AccountAwareStorageBackend<>(rawForeignClusters, null, currentAccount));

        assertThrows(AwsException.class, () -> foreignService.getDbCluster("cluster1"));
        assertThrows(AwsException.class, () -> foreignService.getDbInstance("instance1"));
        assertTrue(foreignService.listDbClusters(null).isEmpty());
        assertTrue(foreignService.listDbInstances(null).isEmpty());
        assertTrue(rawForeignClusters.get("cluster1").isPresent());
        assertTrue(rawForeignInstances.get("instance1").isPresent());
        assertTrue(rawForeignClusters.get(currentAccount + "/cluster1").isEmpty());
        assertTrue(rawForeignInstances.get(currentAccount + "/instance1").isEmpty());

        InMemoryStorage<String, DbCluster> rawOwnedClusters = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> rawOwnedInstances = new InMemoryStorage<>();
        rawOwnedClusters.put("cluster1", persistedCluster(currentAccount, "secret", 7000));
        rawOwnedInstances.put("instance1", persistedInstance(
                "instance1", currentAccount, "secret", 7001));
        RdsService ownedService = proxyStoreService(
                currentResolver, currentConfig, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(rawOwnedInstances, null, currentAccount),
                new AccountAwareStorageBackend<>(rawOwnedClusters, null, currentAccount));

        assertEquals("cluster1", ownedService.getDbCluster("cluster1").getDbClusterIdentifier());
        assertEquals("instance1", ownedService.getDbInstance("instance1").getDbInstanceIdentifier());
        assertTrue(rawOwnedClusters.get("cluster1").isEmpty());
        assertTrue(rawOwnedInstances.get("instance1").isEmpty());
        assertTrue(rawOwnedClusters.get(
                currentAccount + "/us-east-1::cluster1").isPresent());
        assertTrue(rawOwnedInstances.get(
                currentAccount + "/us-east-1::instance1").isPresent());
    }

    @Test
    void sameNamedInstancesInDifferentAccountsUseIndependentRelayKeys() {
        String accountA = "111111111111";
        String accountB = "222222222222";
        InMemoryStorage<String, DbInstance> rawInstances = new InMemoryStorage<>();
        InMemoryStorage<String, DbCluster> rawClusters = new InMemoryStorage<>();
        RdsService serviceA = new RdsService(
                containerManager, proxyManager, ec2Service,
                new RegionResolver("us-east-1", accountA), config,
                new AccountAwareStorageBackend<>(rawInstances, null, accountA),
                new AccountAwareStorageBackend<>(rawClusters, null, accountA),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        RdsService serviceB = new RdsService(
                containerManager, proxyManager, ec2Service,
                new RegionResolver("us-east-1", accountB), config,
                new AccountAwareStorageBackend<>(rawInstances, null, accountB),
                new AccountAwareStorageBackend<>(rawClusters, null, accountB),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());

        DbInstance instanceA = serviceA.createDbInstance(
                "shared-db", "postgres", "16.3", "admin", "secret", "app",
                "db.t3.micro", 20, false, null, null, null);
        DbInstance instanceB = serviceB.createDbInstance(
                "shared-db", "postgres", "16.3", "admin", "secret", "app",
                "db.t3.micro", 20, false, null, null, null);
        String relayKeyA = "rds-resource:" + instanceA.getDbInstanceArn();
        String relayKeyB = "rds-resource:" + instanceB.getDbInstanceArn();

        verify(proxyManager).startProxy(eq(relayKeyA), any(), anyBoolean(), anyInt(),
                any(), anyInt(), any(), any(), any(), any(), any());
        verify(proxyManager).startProxy(eq(relayKeyB), any(), anyBoolean(), anyInt(),
                any(), anyInt(), any(), any(), any(), any(), any());

        org.mockito.Mockito.clearInvocations(proxyManager);
        serviceA.deleteDbInstance("shared-db");

        verify(proxyManager).stopProxy(relayKeyA);
        verify(proxyManager, never()).stopProxy(relayKeyB);
        assertTrue(rawInstances.get(accountA + "/us-east-1::shared-db").isEmpty());
        assertTrue(rawInstances.get(accountB + "/us-east-1::shared-db").isPresent());
    }

    @Test
    void sameNamedInstancesAndClustersAreIsolatedByRegion() {
        when(config.services().rds().mock()).thenReturn(true);
        String accountId = "123456789012";
        InMemoryStorage<String, DbInstance> rawInstances = new InMemoryStorage<>();
        InMemoryStorage<String, DbCluster> rawClusters = new InMemoryStorage<>();
        RdsService service = new RdsService(
                containerManager, proxyManager, ec2Service, regionResolver, config,
                new AccountAwareStorageBackend<>(rawInstances, null, accountId),
                new AccountAwareStorageBackend<>(rawClusters, null, accountId),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());

        DbInstance eastInstance = service.createDbInstance(
                "shared", "postgres", "16.3", "admin", "east-secret", "app",
                "db.t3.micro", 20, false, null, null, null, null, false,
                false, null, Map.of(), List.of(), "us-east-1");
        DbInstance westInstance = service.createDbInstance(
                "shared", "postgres", "16.3", "admin", "west-secret", "app",
                "db.t3.micro", 20, false, null, null, null, null, false,
                false, null, Map.of(), List.of(), "us-west-2");
        DbCluster eastCluster = service.createDbCluster(
                "shared", "aurora-postgresql", "16.3", "admin", "east-cluster",
                "app", false, null, null, null, false, "us-east-1");
        DbCluster westCluster = service.createDbCluster(
                "shared", "aurora-postgresql", "16.3", "admin", "west-cluster",
                "app", false, null, null, null, false, "us-west-2");

        assertEquals(eastInstance.getDbInstanceArn(),
                service.getDbInstance("shared", "us-east-1").getDbInstanceArn());
        assertEquals(westInstance.getDbInstanceArn(),
                service.getDbInstance("shared", "us-west-2").getDbInstanceArn());
        assertEquals(eastCluster.getDbClusterArn(),
                service.getDbCluster("shared", "us-east-1").getDbClusterArn());
        assertEquals(westCluster.getDbClusterArn(),
                service.getDbCluster("shared", "us-west-2").getDbClusterArn());
        assertNotEquals(eastInstance.getContainerStorageResourceId(),
                westInstance.getContainerStorageResourceId());
        assertNotEquals(eastInstance.getContainerStorageResourceId(),
                eastCluster.getContainerStorageResourceId());
        assertTrue(rawInstances.get(accountId + "/us-east-1::shared").isPresent());
        assertTrue(rawInstances.get(accountId + "/us-west-2::shared").isPresent());
        assertTrue(rawClusters.get(accountId + "/us-east-1::shared").isPresent());
        assertTrue(rawClusters.get(accountId + "/us-west-2::shared").isPresent());

        service.modifyDbInstance(
                "shared", "east-updated", null, null, List.of(), "us-east-1");
        service.modifyDbCluster(
                "shared", "east-cluster-updated", null, "us-east-1");
        service.addTagsToResource(
                eastInstance.getDbInstanceArn(), Map.of("region", "east"), "us-east-1");
        assertEquals("east-updated",
                service.getDbInstance("shared", "us-east-1").getMasterPassword());
        assertEquals("west-secret",
                service.getDbInstance("shared", "us-west-2").getMasterPassword());
        assertEquals("east-cluster-updated",
                service.getDbCluster("shared", "us-east-1").getMasterPassword());
        assertEquals("west-cluster",
                service.getDbCluster("shared", "us-west-2").getMasterPassword());
        assertEquals(Map.of("region", "east"),
                service.listTagsForResource(eastInstance.getDbInstanceArn(), "us-east-1"));
        assertEquals(Map.of(),
                service.listTagsForResource(westInstance.getDbInstanceArn(), "us-west-2"));

        service.deleteDbInstance("shared", "us-east-1");
        service.deleteDbCluster("shared", "us-east-1");
        assertThrows(AwsException.class,
                () -> service.getDbInstance("shared", "us-east-1"));
        assertThrows(AwsException.class,
                () -> service.getDbCluster("shared", "us-east-1"));
        assertEquals(westInstance.getDbInstanceArn(),
                service.getDbInstance("shared", "us-west-2").getDbInstanceArn());
        assertEquals(westCluster.getDbClusterArn(),
                service.getDbCluster("shared", "us-west-2").getDbClusterArn());
    }

    @Test
    void proxyRegistrationCannotClaimForeignLegacyTarget() {
        when(config.services().rds().mock()).thenReturn(true);
        String currentAccount = "123456789012";
        String foreignAccount = "222222222222";
        InMemoryStorage<String, DbCluster> rawClusters = new InMemoryStorage<>();
        rawClusters.put("cluster1", persistedCluster(foreignAccount, "secret", 7000));
        AccountAwareStorageBackend<DbCluster> clusters =
                new AccountAwareStorageBackend<>(rawClusters, null, currentAccount);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, currentAccount),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, currentAccount),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, currentAccount),
                clusters);
        service.createDbProxy(
                "app-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH,
                1800, false, Map.of(), "us-east-1");

        AwsException exception = assertThrows(AwsException.class, () ->
                service.registerDbProxyTargets(
                        "app-proxy", "default", List.of("cluster1"), List.of(),
                        0, 0, "us-east-1"));

        assertEquals("DBClusterNotFoundFault", exception.getErrorCode());
        assertTrue(rawClusters.get("cluster1").isPresent());
        assertTrue(rawClusters.get(currentAccount + "/cluster1").isEmpty());
        verify(proxyManager, never()).startProxy(
                any(), any(), anyBoolean(), anyInt(), any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void foreignAccountProxyRestoreDoesNotDeriveVpcFromDefaultAccount() {
        when(config.services().rds().mock()).thenReturn(true);
        String defaultAccount = "123456789012";
        String foreignAccount = "999999999999";
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "foreign-proxy", "us-east-1", foreignAccount, "foreign", 5432);
        proxy.setVpcId(null);
        rawProxies.put(foreignAccount + "/foreign-proxy", proxy);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(rawProxies, null, defaultAccount),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, defaultAccount),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        DbProxy restored = rawProxies.get(
                foreignAccount + "/us-east-1::foreign-proxy").orElseThrow();
        assertNull(restored.getVpcId());
    }

    @Test
    void restorePreservesSameNameLegacyAndCanonicalProxiesAcrossRegions() {
        when(config.services().rds().mock()).thenReturn(true);
        String accountId = "123456789012";
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> rawTargetGroups = new InMemoryStorage<>();
        DbProxy east = persistedProxy(
                "shared-proxy", "us-east-1", accountId, "east", 5432);
        DbProxy west = persistedProxy(
                "shared-proxy", "us-west-2", accountId, "west", 7002);
        DbProxyTargetGroup eastTargetGroup = persistedTargetGroup(
                "shared-proxy", "us-east-1", accountId, "east");
        eastTargetGroup.setCreatedAt(east.getCreatedAt());
        eastTargetGroup.setUpdatedAt(east.getCreatedAt());
        eastTargetGroup.setTargets(List.of(new DbProxyTarget(
                "TRACKED_CLUSTER", "east-cluster",
                "arn:aws:rds:us-east-1:" + accountId + ":cluster:east-cluster",
                "localhost", 5432)));
        DbProxyTargetGroup westTargetGroup = persistedTargetGroup(
                "shared-proxy", "us-west-2", accountId, "west");
        westTargetGroup.setCreatedAt(west.getCreatedAt());
        westTargetGroup.setUpdatedAt(west.getCreatedAt());
        westTargetGroup.setTargets(List.of(new DbProxyTarget(
                "TRACKED_CLUSTER", "west-cluster",
                "arn:aws:rds:us-west-2:" + accountId + ":cluster:west-cluster",
                "localhost", 5432)));
        rawProxies.put(accountId + "/shared-proxy", east);
        rawProxies.put(accountId + "/us-west-2::shared-proxy", west);
        rawTargetGroups.put(accountId + "/shared-proxy", eastTargetGroup);
        rawTargetGroups.put(accountId + "/us-west-2::shared-proxy", westTargetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(rawProxies, null, accountId),
                new AccountAwareStorageBackend<>(rawTargetGroups, null, accountId),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        assertEquals("prx-east", rawProxies.get(
                accountId + "/us-east-1::shared-proxy").orElseThrow().getDbProxyResourceId());
        assertEquals("prx-west", rawProxies.get(
                accountId + "/us-west-2::shared-proxy").orElseThrow().getDbProxyResourceId());
        assertEquals("east-cluster", rawTargetGroups.get(
                accountId + "/us-east-1::shared-proxy").orElseThrow()
                .getTargets().getFirst().getRdsResourceId());
        assertEquals("west-cluster", rawTargetGroups.get(
                accountId + "/us-west-2::shared-proxy").orElseThrow()
                .getTargets().getFirst().getRdsResourceId());
        assertTrue(rawProxies.get(accountId + "/shared-proxy").isEmpty());
        assertTrue(rawTargetGroups.get(accountId + "/shared-proxy").isEmpty());
    }

    @Test
    void failedCrossEngineRestoreKeepsProxyPortReserved() {
        InMemoryStorage<String, DbCluster> clusters = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbCluster cluster = persistedCluster("123456789012", "secret", 7000);
        clusters.put("cluster1", cluster);
        DbProxy proxy = persistedProxy(
                "mysql-proxy", "us-east-1", "123456789012", "mysql", 3306);
        proxy.setEngineFamily("MYSQL");
        proxies.put("us-east-1::mysql-proxy", proxy);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "mysql-proxy", "us-east-1", "123456789012", "mysql");
        targetGroup.setCreatedAt(proxy.getCreatedAt());
        targetGroup.setUpdatedAt(proxy.getCreatedAt());
        targetGroup.setTargets(List.of(new DbProxyTarget(
                "TRACKED_CLUSTER", "cluster1", cluster.getDbClusterArn(), "localhost", 5432)));
        targetGroups.put("us-east-1::mysql-proxy", targetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), clusters);

        service.restorePersistedRuntime();

        DbProxy failed = service.getDbProxy("mysql-proxy", "us-east-1");
        assertEquals("insufficient-resource-limits", failed.getStatus());
        verify(proxyManager, never()).startProxy(
                eq("db-proxy:" + proxy.getDbProxyArn()), any(), anyBoolean(), anyInt(),
                any(), anyInt(), any(), any(), any(), any(), any());

        DbProxy another = service.createDbProxy(
                "another-proxy", "MYSQL", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, 1800, false, Map.of(), "us-east-1");
        assertNotEquals(failed.getProxyPort(), another.getProxyPort());

        service.deregisterDbProxyTargets(
                "mysql-proxy", "default", List.of("cluster1"), List.of());
        service.createDbInstance(
                "mysql-db", "mysql", "8.0", "admin", "secret", "app",
                "db.t3.micro", 20, false, null, null, null);
        service.registerDbProxyTargets(
                "mysql-proxy", "default", List.of(), List.of("mysql-db"), 0, 0);

        assertEquals("available",
                service.getDbProxy("mysql-proxy", "us-east-1").getStatus());
        verify(proxyManager).startProxy(
                eq("db-proxy:" + proxy.getDbProxyArn()), eq(DatabaseEngine.MYSQL),
                anyBoolean(), eq(failed.getProxyPort()), any(), anyInt(),
                any(), any(), any(), any(), any());
    }

    @Test
    void restoreProxyWithoutTargetsRecoversAvailableStatus() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "current", 5432);
        proxy.setStatus("insufficient-resource-limits");
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "current");
        targetGroup.setCreatedAt(proxy.getCreatedAt());
        targetGroup.setUpdatedAt(proxy.getCreatedAt());
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", targetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        assertEquals("available",
                service.getDbProxy("app-proxy", "us-east-1").getStatus());
        verify(proxyManager, never()).startProxy(
                eq("db-proxy:" + proxy.getDbProxyArn()), any(), anyBoolean(), anyInt(),
                any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void proxyTargetGroupRejectsUnsupportedNameCardinalityAndEngine() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null);
        rdsService.createDbProxy("mysql-proxy", "MYSQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());

        AwsException missingGroup = assertThrows(AwsException.class, () ->
                rdsService.describeDbProxyTargets("mysql-proxy", "other"));
        assertEquals("DBProxyTargetGroupNotFoundFault", missingGroup.getErrorCode());

        AwsException multiple = assertThrows(AwsException.class, () ->
                rdsService.registerDbProxyTargets("mysql-proxy", "default",
                        List.of("cluster1", "cluster2"), List.of(), 0, 0));
        assertEquals("InvalidParameterCombination", multiple.getErrorCode());

        AwsException mismatch = assertThrows(AwsException.class, () ->
                rdsService.registerDbProxyTargets("mysql-proxy", "default",
                        List.of("cluster1"), List.of(), 0, 0));
        assertEquals("InvalidParameterValue", mismatch.getErrorCode());
    }

    @Test
    void restorePersistedRuntimeReArmsDbProxyRelayAcrossRestart() {
        StorageBackend<String, DbInstance> instances = new InMemoryStorage<>();
        StorageBackend<String, DbCluster> clusters = new InMemoryStorage<>();
        StorageBackend<String, DbParameterGroup> parameterGroups = new InMemoryStorage<>();
        StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups = new InMemoryStorage<>();
        StorageBackend<String, DbSubnetGroup> subnetGroups = new InMemoryStorage<>();
        // Shared across the two RdsService instances: this is what persists a proxy over a "restart".
        StorageBackend<String, DbProxy> proxies = new InMemoryStorage<>();
        StorageBackend<String, DbProxyTargetGroup> proxyTargetGroups = new InMemoryStorage<>();

        when(containerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("initial-container", "cluster1", "localhost", 5432));

        RdsService initialService = new RdsService(containerManager, proxyManager, ec2Service,
                regionResolver, config, instances, clusters, parameterGroups, clusterParameterGroups,
                subnetGroups, null, null, proxies, proxyTargetGroups);
        initialService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null);
        initialService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        initialService.registerDbProxyTargets("app-proxy", null, List.of("cluster1"), List.of(), 0, 0);

        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        when(restoredContainerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("restored-container", "cluster1", "127.0.0.1", 15432));

        RdsService restoredService = new RdsService(restoredContainerManager, restoredProxyManager, ec2Service,
                regionResolver, config, instances, clusters, parameterGroups, clusterParameterGroups,
                subnetGroups, null, null, proxies, proxyTargetGroups);
        restoredService.restorePersistedRuntime();

        // The proxy survives the restart and its relay is re-armed against the restored backend.
        DbProxy restored = restoredService.getDbProxy("app-proxy");
        assertEquals("app-proxy", restored.getDbProxyName());
        verify(restoredProxyManager).startProxy(eq("db-proxy:" + restored.getDbProxyArn()),
                eq(DatabaseEngine.POSTGRES),
                eq(false), anyInt(), eq("127.0.0.1"), eq(15432), any(),
                eq("admin"), eq("secret"), eq("app"), any());
    }

    @Test
    void restoreDbProxyAuthCallbackKeepsTheTargetAccount() {
        String defaultAccount = "123456789012";
        String targetAccount = "999999999999";
        InMemoryStorage<String, DbInstance> rawInstances = new InMemoryStorage<>();
        InMemoryStorage<String, DbCluster> rawClusters = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> rawTargetGroups = new InMemoryStorage<>();

        // A same-named default-account cluster makes an account-blind callback observably wrong.
        DbCluster defaultCluster = persistedCluster(
                defaultAccount, "default-secret", 7000);
        defaultCluster.setContainerStorageResourceId("cluster-DEFAULT");
        defaultCluster.setDockerVolumeName("floci-rds-default-cluster");
        DbCluster targetCluster = persistedCluster(
                targetAccount, "target-secret", 7001);
        targetCluster.setContainerStorageResourceId("cluster-TARGET");
        targetCluster.setDockerVolumeName("floci-rds-target-cluster");
        rawClusters.put(defaultAccount + "/cluster1", defaultCluster);
        rawClusters.put(targetAccount + "/cluster1", targetCluster);

        DbProxy proxy = new DbProxy();
        proxy.setDbProxyName("app-proxy");
        proxy.setDbProxyArn("arn:aws:rds:us-east-1:" + targetAccount + ":db-proxy:prx-abc");
        proxy.setDbProxyResourceId("prx-abc");
        proxy.setEngineFamily("POSTGRESQL");
        proxy.setProxyPort(5432);
        proxy.setEndpointHost("stale-host");
        proxy.setStatus("available");
        Instant proxyCreatedAt = Instant.now();
        proxy.setCreatedAt(proxyCreatedAt);
        rawProxies.put(targetAccount + "/app-proxy", proxy);

        DbProxyTargetGroup targetGroup = new DbProxyTargetGroup();
        targetGroup.setDbProxyName("app-proxy");
        targetGroup.setTargetGroupName("default");
        targetGroup.setTargetGroupArn("arn:aws:rds:us-east-1:" + targetAccount
                + ":target-group:prx-tg-abc");
        targetGroup.setCreatedAt(proxyCreatedAt);
        targetGroup.setUpdatedAt(proxyCreatedAt);
        targetGroup.setTargets(List.of(new DbProxyTarget("TRACKED_CLUSTER", "cluster1",
                "arn:aws:rds:us-east-1:" + targetAccount + ":cluster:cluster1",
                "stale-host", 5432)));
        rawTargetGroups.put(targetAccount + "/app-proxy", targetGroup);

        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        when(restoredContainerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("restored-container", "cluster1",
                        "127.0.0.1", 15432));

        RdsService restoredService = new RdsService(restoredContainerManager, restoredProxyManager,
                ec2Service, regionResolver, config,
                new AccountAwareStorageBackend<>(rawInstances, null, defaultAccount),
                new AccountAwareStorageBackend<>(rawClusters, null, defaultAccount),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, defaultAccount),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, defaultAccount),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, defaultAccount),
                null, null,
                new AccountAwareStorageBackend<>(rawProxies, null, defaultAccount),
                new AccountAwareStorageBackend<>(rawTargetGroups, null, defaultAccount));

        restoredService.restorePersistedRuntime();

        ArgumentCaptor<RdsAuthProxy.PasswordValidator> validator =
                ArgumentCaptor.forClass(RdsAuthProxy.PasswordValidator.class);
        verify(restoredProxyManager).startProxy(eq("db-proxy:" + proxy.getDbProxyArn()),
                eq(DatabaseEngine.POSTGRES),
                eq(false), eq(5432), eq("127.0.0.1"), eq(15432), any(), eq("admin"),
                eq("target-secret"), eq("app"), validator.capture());
        assertTrue(validator.getValue().validate("admin", "target-secret"));
        assertFalse(validator.getValue().validate("admin", "default-secret"));
        assertTrue(rawProxies.get(targetAccount + "/us-east-1::app-proxy").isPresent());
        assertTrue(rawTargetGroups.get(targetAccount + "/us-east-1::app-proxy").isPresent());
        assertTrue(rawProxies.get(targetAccount + "/app-proxy").isEmpty());
        assertTrue(rawTargetGroups.get(targetAccount + "/app-proxy").isEmpty());
    }

    // ── Option Groups ─────────────────────────────────────────────────────────

    @Test
    void createOptionGroupReturnsArnAndNoOptions() {
        OptionGroup group = rdsService.createOptionGroup(
                "og1", "mysql", "8.0", "my option group");

        assertEquals("og1", group.getOptionGroupName());
        assertEquals("mysql", group.getEngineName());
        assertEquals("8.0", group.getMajorEngineVersion());
        assertEquals("my option group", group.getOptionGroupDescription());
        assertEquals("arn:aws:rds:us-east-1:123456789012:og:og1", group.getOptionGroupArn());
        assertTrue(group.isAllowsVpcAndNonVpcInstanceMemberships());
        assertTrue(group.getOptions().isEmpty());
    }

    @Test
    void createOptionGroupRejectsDuplicateName() {
        rdsService.createOptionGroup("og1", "mysql", "8.0", "first");

        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.createOptionGroup("og1", "mysql", "8.0", "second"));

        assertEquals("OptionGroupAlreadyExistsFault", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }

    @Test
    void createOptionGroupCannotShadowAManagedDefaultName() {
        // A default group's name contains ':', which the AWS name constraint forbids, so the
        // name check rejects it before the already-exists check can ever be reached.
        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.createOptionGroup(
                        "default:mysql-8-0", "mysql", "8.0", "shadowing a default"));

        assertEquals("InvalidParameterValue", exception.getErrorCode());
        assertEquals("mysql", rdsService.getOptionGroup("default:mysql-8-0").getEngineName());
    }

    @Test
    void createOptionGroupRejectsUnknownEngine() {
        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.createOptionGroup("og1", "cassandra", "5.0", "nope"));

        assertEquals("InvalidParameterValue", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }

    @ParameterizedTest
    @CsvSource({
            "1og",              // must start with a letter
            "-og",              // must start with a letter
            "og-",              // can't end with a hyphen
            "og--1",            // can't contain two consecutive hyphens
            "og_1",             // only letters, numbers and hyphens
            "og name"           // only letters, numbers and hyphens
    })
    void createOptionGroupRejectsNamesThatViolateAwsConstraints(String name) {
        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.createOptionGroup(name, "mysql", "8.0", "bad name"));

        assertEquals("InvalidParameterValue", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }

    @Test
    void createOptionGroupRejectsNameLongerThan255Characters() {
        String name = "o" + "g".repeat(255);

        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.createOptionGroup(name, "mysql", "8.0", "too long"));

        assertEquals("InvalidParameterValue", exception.getErrorCode());
    }

    @Test
    void createOptionGroupAcceptsHyphenatedAlphanumericName() {
        OptionGroup group = rdsService.createOptionGroup(
                "my-og-1", "mysql", "8.0", "valid name");

        assertEquals("my-og-1", group.getOptionGroupName());
    }

    @Test
    void getOptionGroupThrowsOptionGroupNotFoundFaultForUnknownName() {
        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.getOptionGroup("missing"));

        assertEquals("OptionGroupNotFoundFault", exception.getErrorCode());
        assertEquals(404, exception.getHttpStatus());
    }

    @Test
    void listOptionGroupsAlwaysIncludesManagedDefaults() {
        List<String> names = rdsService.listOptionGroups(null, null, null).stream()
                .map(OptionGroup::getOptionGroupName)
                .toList();

        assertTrue(names.contains("default:mysql-8-0"), names.toString());
        assertTrue(names.contains("default:postgres-16"), names.toString());
        assertTrue(names.contains("default:mariadb-11-4"), names.toString());
    }

    @Test
    void listOptionGroupsIncludesCreatedGroupsAlongsideDefaults() {
        rdsService.createOptionGroup("og1", "mysql", "8.0", "mine");

        List<String> names = rdsService.listOptionGroups(null, null, null).stream()
                .map(OptionGroup::getOptionGroupName)
                .toList();

        assertTrue(names.contains("og1"), names.toString());
        assertTrue(names.contains("default:mysql-8-0"), names.toString());
    }

    @Test
    void listOptionGroupsFiltersByEngineName() {
        rdsService.createOptionGroup("og-oracle", "oracle-ee", "19", "oracle");

        Collection<OptionGroup> groups = rdsService.listOptionGroups(null, "oracle-ee", null);

        assertFalse(groups.isEmpty());
        assertTrue(groups.stream().allMatch(group -> "oracle-ee".equals(group.getEngineName())));
        assertTrue(groups.stream()
                .anyMatch(group -> "og-oracle".equals(group.getOptionGroupName())));
    }

    @Test
    void listOptionGroupsFiltersByMajorEngineVersion() {
        Collection<OptionGroup> groups = rdsService.listOptionGroups(null, "mysql", "8.4");

        assertEquals(1, groups.size());
        assertEquals("default:mysql-8-4", groups.iterator().next().getOptionGroupName());
    }

    @Test
    void listOptionGroupsRejectsMajorEngineVersionWithoutEngineName() {
        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.listOptionGroups(null, null, "8.0"));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
    }

    @Test
    void listOptionGroupsByUnknownNameThrowsInsteadOfReturningEmptyList() {
        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.listOptionGroups("missing", null, null));

        assertEquals("OptionGroupNotFoundFault", exception.getErrorCode());
        assertEquals(404, exception.getHttpStatus());
    }

    @Test
    void listOptionGroupsByNameResolvesManagedDefault() {
        Collection<OptionGroup> groups =
                rdsService.listOptionGroups("default:postgres-16", null, null);

        assertEquals(1, groups.size());
        OptionGroup group = groups.iterator().next();
        assertEquals("postgres", group.getEngineName());
        assertEquals("16", group.getMajorEngineVersion());
    }

    @Test
    void modifyOptionGroupAddsOptions() {
        rdsService.createOptionGroup("og1", "mariadb", "11.4", "mine");
        OptionGroupOption option = new OptionGroupOption("MARIADB_AUDIT_PLUGIN");
        option.setPort(11211);
        option.setOptionSettings(new java.util.LinkedHashMap<>(
                Map.of("SERVER_AUDIT_EVENTS", "CONNECT")));
        option.setVpcSecurityGroupMemberships(List.of("sg-123"));

        OptionGroup group = rdsService.modifyOptionGroup("og1", List.of(option), List.of());

        assertEquals(1, group.getOptions().size());
        OptionGroupOption stored = group.getOptions().getFirst();
        assertEquals("MARIADB_AUDIT_PLUGIN", stored.getOptionName());
        assertEquals(11211, stored.getPort());
        assertEquals("CONNECT", stored.getOptionSettings().get("SERVER_AUDIT_EVENTS"));
        assertEquals(List.of("sg-123"), stored.getVpcSecurityGroupMemberships());
        assertEquals(1, rdsService.getOptionGroup("og1").getOptions().size());
    }

    @Test
    void modifyOptionGroupUpdatesAnExistingOptionInPlace() {
        rdsService.createOptionGroup("og1", "mariadb", "11.4", "mine");
        OptionGroupOption first = new OptionGroupOption("MARIADB_AUDIT_PLUGIN");
        first.setOptionSettings(new java.util.LinkedHashMap<>(
                Map.of("SERVER_AUDIT_EVENTS", "CONNECT")));
        rdsService.modifyOptionGroup("og1", List.of(first), List.of());

        OptionGroupOption update = new OptionGroupOption("MARIADB_AUDIT_PLUGIN");
        update.setOptionSettings(new java.util.LinkedHashMap<>(
                Map.of("SERVER_AUDIT_EVENTS", "CONNECT,QUERY")));
        OptionGroup group = rdsService.modifyOptionGroup("og1", List.of(update), List.of());

        assertEquals(1, group.getOptions().size());
        assertEquals("CONNECT,QUERY",
                group.getOptions().getFirst().getOptionSettings().get("SERVER_AUDIT_EVENTS"));
    }

    @Test
    void modifyOptionGroupRemovesOptions() {
        rdsService.createOptionGroup("og1", "mysql", "8.0", "mine");
        rdsService.modifyOptionGroup(
                "og1", List.of(new OptionGroupOption("MEMCACHED")), List.of());

        OptionGroup group = rdsService.modifyOptionGroup("og1", List.of(), List.of("MEMCACHED"));

        assertTrue(group.getOptions().isEmpty());
        assertTrue(rdsService.getOptionGroup("og1").getOptions().isEmpty());
    }

    @Test
    void modifyOptionGroupThrowsForUnknownGroup() {
        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.modifyOptionGroup("missing", List.of(), List.of()));

        assertEquals("OptionGroupNotFoundFault", exception.getErrorCode());
        assertEquals(404, exception.getHttpStatus());
    }

    @Test
    void modifyOptionGroupRejectsManagedDefault() {
        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.modifyOptionGroup("default:mysql-8-0",
                        List.of(new OptionGroupOption("MEMCACHED")), List.of()));

        assertEquals("InvalidOptionGroupStateFault", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }

    @Test
    void deleteOptionGroupRemovesTheGroup() {
        rdsService.createOptionGroup("og1", "mysql", "8.0", "mine");

        rdsService.deleteOptionGroup("og1");

        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.getOptionGroup("og1"));
        assertEquals("OptionGroupNotFoundFault", exception.getErrorCode());
    }

    @Test
    void deleteOptionGroupThrowsForUnknownGroup() {
        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.deleteOptionGroup("missing"));

        assertEquals("OptionGroupNotFoundFault", exception.getErrorCode());
        assertEquals(404, exception.getHttpStatus());
    }

    @Test
    void deleteOptionGroupRejectsManagedDefault() {
        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.deleteOptionGroup("default:mysql-8-0"));

        assertEquals("InvalidOptionGroupStateFault", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }

    @Test
    void deleteOptionGroupRejectsGroupStillAttachedToAnInstance() {
        rdsService.createOptionGroup("og1", "mysql", "8.0", "mine");
        createInstanceWithOptionGroup("mydb", "mysql", "8.0", "og1");

        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.deleteOptionGroup("og1"));

        assertEquals("InvalidOptionGroupStateFault", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }

    @Test
    void optionGroupsAreScopedByRegion() {
        rdsService.createOptionGroup("og1", "mysql", "8.0", "mine", Map.of(), "us-east-1");

        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.getOptionGroup("og1", "eu-west-1"));

        assertEquals("OptionGroupNotFoundFault", exception.getErrorCode());
        assertEquals("arn:aws:rds:us-east-1:123456789012:og:og1",
                rdsService.getOptionGroup("og1", "us-east-1").getOptionGroupArn());
    }

    @Test
    void createDbInstanceStoresAttachedOptionGroup() {
        rdsService.createOptionGroup("og1", "mysql", "8.0", "mine");

        DbInstance instance = createInstanceWithOptionGroup("mydb", "mysql", "8.0", "og1");

        assertEquals("og1", instance.getOptionGroupName());
        assertEquals("og1", rdsService.getDbInstance("mydb").getOptionGroupName());
    }

    @Test
    void createDbInstanceRejectsUnknownOptionGroup() {
        AwsException exception = assertThrows(AwsException.class,
                () -> createInstanceWithOptionGroup("mydb", "mysql", "8.0", "missing"));

        assertEquals("OptionGroupNotFoundFault", exception.getErrorCode());
    }

    @Test
    void createDbInstanceRejectsOptionGroupForAnotherEngine() {
        rdsService.createOptionGroup("og1", "mysql", "8.0", "mine");

        AwsException exception = assertThrows(AwsException.class,
                () -> createInstanceWithOptionGroup("mydb", "postgres", "16.3", "og1"));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
    }

    @Test
    void createDbInstanceRejectsOptionGroupForAnotherMajorEngineVersion() {
        rdsService.createOptionGroup("og1", "mysql", "8.0", "mine");

        AwsException exception = assertThrows(AwsException.class,
                () -> createInstanceWithOptionGroup("mydb", "mysql", "8.4.3", "og1"));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
    }

    @Test
    void createDbInstanceRejectsPostgresOptionGroupForAnotherMajorEngineVersion() {
        rdsService.createOptionGroup("og1", "postgres", "13", "mine");

        AwsException exception = assertThrows(AwsException.class,
                () -> createInstanceWithOptionGroup("mydb", "postgres", "16.3", "og1"));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
    }

    @Test
    void createDbInstanceAcceptsOptionGroupMatchingTheInstanceMajorEngineVersion() {
        rdsService.createOptionGroup("og1", "mysql", "8.0", "mine");

        DbInstance instance = createInstanceWithOptionGroup("mydb", "mysql", "8.0.36", "og1");

        assertEquals("og1", instance.getOptionGroupName());
    }

    @Test
    void createDbInstanceAcceptsPostgresOptionGroupRegardlessOfTheMinorVersion() {
        rdsService.createOptionGroup("og1", "postgres", "16", "mine");

        DbInstance instance = createInstanceWithOptionGroup("mydb", "postgres", "16.3", "og1");

        assertEquals("og1", instance.getOptionGroupName());
    }

    @Test
    void createDbInstanceAcceptsManagedDefaultOptionGroup() {
        DbInstance instance =
                createInstanceWithOptionGroup("mydb", "mysql", "8.0", "default:mysql-8-0");

        assertEquals("default:mysql-8-0", instance.getOptionGroupName());
    }

    @Test
    void optionGroupIsTaggableByArn() {
        OptionGroup group = rdsService.createOptionGroup("og1", "mysql", "8.0", "mine");

        rdsService.addTagsToResource(group.getOptionGroupArn(), Map.of("env", "dev"));

        assertEquals(Map.of("env", "dev"),
                rdsService.listTagsForResource(group.getOptionGroupArn()));
    }

    @Test
    void defaultOptionGroupCannotBeTagged() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.addTagsToResource(
                        "arn:aws:rds:us-east-1:123456789012:og:default:mysql-8-0",
                        Map.of("env", "dev")));

        assertEquals("InvalidOptionGroupStateFault", exception.getErrorCode());
    }

    @Test
    void modifyDbInstanceAttachesOptionGroup() {
        rdsService.createOptionGroup("og1", "mysql", "8.0", "mine");
        createInstanceWithOptionGroup("mydb", "mysql", "8.0", null);

        DbInstance modified = rdsService.modifyDbInstance(
                "mydb", null, null, null, List.of(), "og1", "us-east-1");

        assertEquals("og1", modified.getOptionGroupName());
        assertEquals("og1", rdsService.getDbInstance("mydb").getOptionGroupName());
    }

    @Test
    void modifyDbInstanceRejectsOptionGroupForAnotherEngine() {
        rdsService.createOptionGroup("og1", "postgres", "16", "mine");
        createInstanceWithOptionGroup("mydb", "mysql", "8.0", null);

        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.modifyDbInstance(
                        "mydb", null, null, null, List.of(), "og1", "us-east-1"));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
    }

    @Test
    void modifyDbInstanceRejectsOptionGroupForAnotherMajorEngineVersion() {
        rdsService.createOptionGroup("og1", "mysql", "8.4", "mine");
        createInstanceWithOptionGroup("mydb", "mysql", "8.0.36", null);

        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.modifyDbInstance(
                        "mydb", null, null, null, List.of(), "og1", "us-east-1"));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
    }

    @Test
    void modifyDbInstanceAcceptsOptionGroupMatchingTheInstanceMajorEngineVersion() {
        rdsService.createOptionGroup("og1", "mysql", "8.0", "mine");
        createInstanceWithOptionGroup("mydb", "mysql", "8.0.36", null);

        DbInstance modified = rdsService.modifyDbInstance(
                "mydb", null, null, null, List.of(), "og1", "us-east-1");

        assertEquals("og1", modified.getOptionGroupName());
    }

    @Test
    void optionGroupsUseTheInjectedAccountAwareStorage() {
        String accountId = "123456789012";
        InMemoryStorage<String, OptionGroup> rawOptionGroups = new InMemoryStorage<>();
        RdsService service = optionGroupStoreService(regionResolver,
                new AccountAwareStorageBackend<>(rawOptionGroups, null, accountId));

        service.createOptionGroup("og1", "mysql", "8.0", "mine", Map.of(), "us-east-1");

        assertTrue(rawOptionGroups.get(accountId + "/us-east-1::og1").isPresent());
    }

    @Test
    void sameNamedOptionGroupsInDifferentAccountsAreIsolated() {
        String accountA = "111111111111";
        String accountB = "222222222222";
        InMemoryStorage<String, OptionGroup> rawOptionGroups = new InMemoryStorage<>();
        RdsService serviceA = optionGroupStoreService(
                new RegionResolver("us-east-1", accountA),
                new AccountAwareStorageBackend<>(rawOptionGroups, null, accountA));
        RdsService serviceB = optionGroupStoreService(
                new RegionResolver("us-east-1", accountB),
                new AccountAwareStorageBackend<>(rawOptionGroups, null, accountB));

        serviceA.createOptionGroup("og1", "mysql", "8.0", "account a", Map.of(), "us-east-1");
        serviceB.createOptionGroup("og1", "postgres", "16", "account b", Map.of(), "us-east-1");
        serviceA.deleteOptionGroup("og1", "us-east-1");

        assertThrows(AwsException.class, () -> serviceA.getOptionGroup("og1", "us-east-1"));
        assertEquals("postgres", serviceB.getOptionGroup("og1", "us-east-1").getEngineName());
    }

    private RdsService optionGroupStoreService(
            RegionResolver resolver, StorageBackend<String, OptionGroup> optionGroups) {
        return new RdsService(containerManager, proxyManager, ec2Service, resolver, config,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), null, null, null,
                new InMemoryStorage<>(), new InMemoryStorage<>(), optionGroups, null, kmsService);
    }

    private DbInstance createInstanceWithOptionGroup(
            String id, String engine, String engineVersion, String optionGroupName) {
        return rdsService.createDbInstance(id, engine, engineVersion, "admin", "password",
                "dbname", "db.t3.micro", 20, false, null, null, null, null, false, false,
                null, Map.of(), List.of(), optionGroupName, null, true);
    }

    private RdsService newService(RdsContainerManager containerManager,
                                  RdsProxyManager proxyManager,
                                  StorageBackend<String, DbInstance> instances,
                                  StorageBackend<String, DbCluster> clusters,
                                  StorageBackend<String, DbParameterGroup> parameterGroups,
                                  StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups,
                                  StorageBackend<String, DbSubnetGroup> subnetGroups) {
        return new RdsService(containerManager, proxyManager, ec2Service, regionResolver, config,
                instances, clusters, parameterGroups, clusterParameterGroups, subnetGroups,
                null, null, null, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), null, kmsService);
    }

    private RdsService newService(RdsContainerManager containerManager,
                                  RdsProxyManager proxyManager,
                                  StorageBackend<String, DbInstance> instances,
                                  StorageBackend<String, DbCluster> clusters,
                                  StorageBackend<String, DbParameterGroup> parameterGroups,
                                  StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups,
                                  SecretsManagerService secretsManager) {
        return new RdsService(containerManager, proxyManager, ec2Service, regionResolver, config,
                instances, clusters, parameterGroups, clusterParameterGroups, new InMemoryStorage<>(),
                secretsManager, null, null, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), null, kmsService);
    }

    private RdsService proxyStoreService(
            RegionResolver resolver, EmulatorConfig serviceConfig,
            StorageBackend<String, DbProxy> proxies,
            StorageBackend<String, DbProxyTargetGroup> targetGroups,
            StorageBackend<String, DbInstance> instances,
            StorageBackend<String, DbCluster> clusters) {
        return new RdsService(containerManager, proxyManager, ec2Service, resolver, serviceConfig,
                instances, clusters, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), null, null, proxies, targetGroups);
    }

    private static DbProxy persistedProxy(
            String name, String region, String accountId, String suffix, int proxyPort) {
        DbProxy proxy = new DbProxy();
        proxy.setDbProxyName(name);
        proxy.setDbProxyArn("arn:aws:rds:" + region + ":" + accountId
                + ":db-proxy:prx-" + suffix);
        proxy.setDbProxyResourceId("prx-" + suffix);
        proxy.setEngineFamily("POSTGRESQL");
        proxy.setRoleArn("arn:aws:iam::" + accountId + ":role/proxy");
        proxy.setVpcId("vpc-default");
        proxy.setVpcSubnetIds(PROXY_SUBNET_IDS);
        proxy.setAuth(PROXY_AUTH);
        proxy.setProxyPort(proxyPort);
        proxy.setEndpointHost("localhost");
        proxy.setStatus("available");
        proxy.setCreatedAt(persistedGenerationTime(suffix));
        proxy.setUpdatedAt(proxy.getCreatedAt());
        return proxy;
    }

    private static DbProxyTargetGroup persistedTargetGroup(
            String proxyName, String region, String accountId, String suffix) {
        DbProxyTargetGroup targetGroup = new DbProxyTargetGroup();
        targetGroup.setDbProxyName(proxyName);
        targetGroup.setTargetGroupName("default");
        targetGroup.setTargetGroupArn("arn:aws:rds:" + region + ":" + accountId
                + ":target-group:prx-tg-" + suffix);
        targetGroup.setDefaultTargetGroup(true);
        targetGroup.setCreatedAt(persistedGenerationTime(suffix));
        targetGroup.setUpdatedAt(targetGroup.getCreatedAt());
        return targetGroup;
    }

    private static void assertProxyTargetGroupState(
            DbProxyTargetGroup expected, DbProxyTargetGroup actual) {
        assertEquals(expected.getDbProxyName(), actual.getDbProxyName());
        assertEquals(expected.getTargetGroupName(), actual.getTargetGroupName());
        assertEquals(expected.getTargetGroupArn(), actual.getTargetGroupArn());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.isDefaultTargetGroup(), actual.isDefaultTargetGroup());
        assertEquals(expected.getCreatedAt(), actual.getCreatedAt());
        assertEquals(expected.getUpdatedAt(), actual.getUpdatedAt());
        assertEquals(expected.getMaxConnectionsPercent(), actual.getMaxConnectionsPercent());
        assertEquals(expected.getMaxIdleConnectionsPercent(), actual.getMaxIdleConnectionsPercent());
        assertEquals(expected.getConnectionBorrowTimeout(), actual.getConnectionBorrowTimeout());
        assertEquals(expected.getInitQuery(), actual.getInitQuery());
        assertEquals(expected.getSessionPinningFilters(), actual.getSessionPinningFilters());
        assertEquals(expected.getTargets().size(), actual.getTargets().size());
        for (int index = 0; index < expected.getTargets().size(); index++) {
            DbProxyTarget expectedTarget = expected.getTargets().get(index);
            DbProxyTarget actualTarget = actual.getTargets().get(index);
            assertEquals(expectedTarget.getType(), actualTarget.getType());
            assertEquals(expectedTarget.getRdsResourceId(), actualTarget.getRdsResourceId());
            assertEquals(expectedTarget.getTargetArn(), actualTarget.getTargetArn());
            assertEquals(expectedTarget.getEndpoint(), actualTarget.getEndpoint());
            assertEquals(expectedTarget.getPort(), actualTarget.getPort());
            assertEquals(expectedTarget.getTargetHealth(), actualTarget.getTargetHealth());
        }
    }

    private static Instant persistedGenerationTime(String suffix) {
        return Instant.ofEpochSecond(Integer.toUnsignedLong(suffix.hashCode()));
    }

    private static DbProxyAuth proxyAuthWithUserName(String userName) {
        DbProxyAuth auth = new DbProxyAuth(
                "SECRETS", PROXY_AUTH.getFirst().getSecretArn(), "DISABLED", null, null);
        auth.setUserName(userName);
        return auth;
    }

    private static List<Subnet> defaultSubnets() {
        return List.of(
                subnet("subnet-default-a", "vpc-default", "us-east-1a"),
                subnet("subnet-default-b", "vpc-default", "us-east-1b"));
    }

    private static DbInstance persistedInstance(
            String instanceId, String accountId, String password, int proxyPort) {
        DbInstance instance = new DbInstance(
                instanceId, DatabaseEngine.POSTGRES, "16.3", "admin", password,
                "app", "db.t3.micro", 20, DbInstanceStatus.AVAILABLE,
                new DbEndpoint("stale-host", proxyPort), false, null, null,
                Instant.now(), proxyPort);
        instance.setDbInstanceArn(
                "arn:aws:rds:us-east-1:" + accountId + ":db:" + instanceId);
        return instance;
    }

    private static DbCluster persistedCluster(String accountId, String password, int proxyPort) {
        DbCluster cluster = new DbCluster("cluster1", DatabaseEngine.POSTGRES, "16.3",
                "admin", password, "app", DbInstanceStatus.AVAILABLE,
                new DbEndpoint("stale-host", proxyPort), new DbEndpoint("stale-host", proxyPort),
                false, List.of(), null, Instant.now(), proxyPort);
        cluster.setDbClusterArn("arn:aws:rds:us-east-1:" + accountId + ":cluster:cluster1");
        cluster.setVolumeId("volume-" + accountId);
        return cluster;
    }

    private static Subnet subnet(String subnetId, String vpcId, String availabilityZone) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setVpcId(vpcId);
        subnet.setAvailabilityZone(availabilityZone);
        return subnet;
    }

    @Test
    void createDbSubnetGroupStoresTheTagsItIsGiven() {
        rdsService.createDbSubnetGroup("tagged", "d", List.of("subnet-default-a", "subnet-default-b"), "us-east-1",
                Map.of("Name", "tagged", "env", "tst"));
        String arn = "arn:aws:rds:us-east-1:123456789012:subgrp:tagged";

        assertEquals(Map.of("Name", "tagged", "env", "tst"), rdsService.listTagsForResource(arn, "us-east-1"));

        // later tag calls build on the create-time tags rather than replacing them
        rdsService.addTagsToResource(arn, Map.of("env", "stg", "extra", "yes"), "us-east-1");
        assertEquals(Map.of("Name", "tagged", "env", "stg", "extra", "yes"),
                rdsService.listTagsForResource(arn, "us-east-1"));

        // and the tags are what a restart reads back, not a side table
        assertEquals(Map.of("Name", "tagged", "env", "stg", "extra", "yes"),
                rdsService.getDbSubnetGroup("tagged", "us-east-1").getTags());
    }

    @Test
    void createDbInstanceKeepsTheEngineNameTheRequestGave() {
        rdsService.createDbCluster("aurora", "aurora-postgresql", null, "admin", "secret99password",
                null, false, null);
        rdsService.createDbInstance("member", "aurora-postgresql", "16",
                "admin", "password", null, "db.t3.medium",
                20, false, null, null, "aurora", null, false);
        rdsService.createDbInstance("member-2", null, "16",
                "admin", "password", null, "db.t3.medium",
                20, false, null, null, "aurora", null, false);
        rdsService.createDbInstance("plain", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);

        assertEquals("aurora-postgresql", rdsService.getDbInstance("member").getEngineIdentifier());
        // a member created without Engine takes the cluster's
        assertEquals("aurora-postgresql", rdsService.getDbInstance("member-2").getEngineIdentifier());
        assertEquals("postgres", rdsService.getDbInstance("plain").getEngineIdentifier());
    }

    @Test
    void restoreGivesAPersistedAuroraMemberItsClusterEngineName() {
        // A member written by a floci that predates engineIdentifier carries only the enum, which
        // says postgres for an aurora-postgresql cluster. The cluster still knows, and the restore
        // reads it there, so the member is reported and filtered as aurora-postgresql.
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        InMemoryStorage<String, DbCluster> clusters = new InMemoryStorage<>();
        RdsService service = newService(containerManager, proxyManager, instances, clusters,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        service.createDbCluster("aurora", "aurora-postgresql", null, "admin", "secret99password",
                null, false, null);

        DbInstance legacyMember = new DbInstance();
        legacyMember.setDbInstanceIdentifier("legacy-member");
        legacyMember.setEngine(DatabaseEngine.POSTGRES);
        legacyMember.setDbClusterIdentifier("aurora");
        legacyMember.setDbInstanceArn("arn:aws:rds:us-east-1:123456789012:db:legacy-member");
        legacyMember.setStatus(DbInstanceStatus.AVAILABLE);
        instances.put("us-east-1::legacy-member", legacyMember);
        DbInstance legacyStandalone = new DbInstance();
        legacyStandalone.setDbInstanceIdentifier("legacy-plain");
        legacyStandalone.setEngine(DatabaseEngine.MARIADB);
        legacyStandalone.setDbInstanceArn("arn:aws:rds:us-east-1:123456789012:db:legacy-plain");
        legacyStandalone.setStatus(DbInstanceStatus.AVAILABLE);
        instances.put("us-east-1::legacy-plain", legacyStandalone);

        // a member of a cluster that predates the field itself: nothing certain to write, so
        // nothing is written — the enum would persist postgres for what may be Aurora
        DbCluster nameless = new DbCluster();
        nameless.setDbClusterIdentifier("old-cluster");
        nameless.setEngine(DatabaseEngine.POSTGRES);
        nameless.setStatus(DbInstanceStatus.AVAILABLE);
        nameless.setDbClusterArn("arn:aws:rds:us-east-1:123456789012:cluster:old-cluster");
        clusters.put("us-east-1::old-cluster", nameless);
        DbInstance orphanedMember = new DbInstance();
        orphanedMember.setDbInstanceIdentifier("old-member");
        orphanedMember.setEngine(DatabaseEngine.POSTGRES);
        orphanedMember.setDbClusterIdentifier("old-cluster");
        orphanedMember.setDbInstanceArn("arn:aws:rds:us-east-1:123456789012:db:old-member");
        orphanedMember.setStatus(DbInstanceStatus.AVAILABLE);
        instances.put("us-east-1::old-member", orphanedMember);

        service.restorePersistedRuntime();

        assertEquals("aurora-postgresql", service.getDbInstance("legacy-member").getEngineIdentifier());
        assertEquals("mariadb", service.getDbInstance("legacy-plain").getEngineIdentifier());
        assertNull(service.getDbInstance("old-member").getEngineIdentifier());
    }

    private static final String KEY_ARN = "arn:aws:kms:us-east-1:123456789012:key/k1";

    private KmsKey knownKey(String... acceptedForms) {
        KmsKey key = new KmsKey();
        key.setKeyId("k1");
        key.setArn(KEY_ARN);
        key.setEnabled(true);
        key.setKeyState("Enabled");
        for (String form : acceptedForms) {
            doReturn(key).when(kmsService).describeKey(form, "us-east-1");
        }
        return key;
    }

    @Test
    void createDbInstanceResolvesEveryKmsKeyFormToTheKeyArn() {
        knownKey(KEY_ARN, "k1", "alias/rds", "arn:aws:kms:us-east-1:123456789012:alias/rds");
        int n = 0;
        for (String form : List.of("k1", "alias/rds", "arn:aws:kms:us-east-1:123456789012:alias/rds")) {
            String id = "db" + (n++);
            rdsService.createDbInstance(id, "postgres", "13", "admin", "password", "dbname",
                    "db.t3.micro", 20, false, null, null, null, null, false, false, null,
                    Map.of(), List.of(), null, null, true,
                    new DbInstanceSettings(true, form, null, null, null, null));
            assertEquals(KEY_ARN, rdsService.getDbInstance(id).getKmsKeyId(), form);
        }
    }

    @Test
    void createDbInstanceRejectsAKmsKeyItCannotUse() {
        AwsException missing = assertThrows(AwsException.class, () -> rdsService.createDbInstance(
                "mydb", "postgres", "13", "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(true, "alias/does-not-exist", null, null, null, null)));
        assertEquals("KMSKeyNotAccessibleFault", missing.getErrorCode());
        assertEquals("The specified KMS key [alias/does-not-exist] does not exist, is not enabled "
                + "or you do not have permissions to access it.", missing.getMessage());
        assertThrows(AwsException.class, () -> rdsService.getDbInstance("mydb"));
        // refused before any side effect: no container was started for the rejected create
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());

        KmsKey disabled = knownKey("k1");
        disabled.setEnabled(false);
        AwsException notEnabled = assertThrows(AwsException.class, () -> rdsService.createDbInstance(
                "mydb", "postgres", "13", "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(true, "k1", null, null, null, null)));
        assertEquals("KMSKeyNotAccessibleFault", notEnabled.getErrorCode());
    }

    @Test
    void createDbInstanceStoresStorageAndBackupSettings() {
        knownKey(KEY_ARN);
        DbInstanceSettings settings = new DbInstanceSettings(true,
                "arn:aws:kms:us-east-1:123456789012:key/k1", 7, "23:30-00:00", "Sun:03:08-Sun:03:38", true);
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true, settings);

        DbInstance stored = rdsService.getDbInstance("mydb");
        assertTrue(stored.isStorageEncrypted());
        assertEquals("arn:aws:kms:us-east-1:123456789012:key/k1", stored.getKmsKeyId());
        assertEquals(7, stored.getBackupRetentionPeriod());
        assertEquals("23:30-00:00", stored.getPreferredBackupWindow());
        assertEquals("sun:03:08-sun:03:38", stored.getPreferredMaintenanceWindow());
        assertTrue(stored.isCopyTagsToSnapshot());
    }

    @Test
    void createDbInstanceWithoutSettingsUsesAwsDefaults() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);

        DbInstance stored = rdsService.getDbInstance("mydb");
        assertFalse(stored.isStorageEncrypted());
        assertNull(stored.getKmsKeyId());
        assertEquals(1, stored.getBackupRetentionPeriod());
        assertFalse(stored.isCopyTagsToSnapshot());
    }

    @Test
    void createDbInstanceRejectsKmsKeyWithoutEncryption() {
        DbInstanceSettings settings = new DbInstanceSettings(false,
                "arn:aws:kms:us-east-1:123456789012:key/k1", null, null, null, null);
        AwsException e = assertThrows(AwsException.class, () -> rdsService.createDbInstance(
                "mydb", "postgres", "13", "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true, settings));
        assertEquals("InvalidParameterCombination", e.getErrorCode());
        assertThrows(AwsException.class, () -> rdsService.getDbInstance("mydb"));

        // leaving StorageEncrypted out is the same as false on a live account
        AwsException omitted = assertThrows(AwsException.class, () -> rdsService.createDbInstance(
                "mydb", "postgres", "13", "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, "arn:aws:kms:us-east-1:123456789012:key/k1", null, null, null, null)));
        assertEquals("InvalidParameterCombination", omitted.getErrorCode());
    }

    @Test
    void createDbInstanceRejectsShortOrOverlappingWindows() {
        AwsException tooShort = assertThrows(AwsException.class, () -> rdsService.createDbInstance(
                "mydb", "postgres", "13", "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, null, "02:00-02:10", null, null)));
        assertEquals("InvalidParameterValue", tooShort.getErrorCode());
        assertEquals("Backup window must be at least 30 minutes.", tooShort.getMessage());

        AwsException overlapping = assertThrows(AwsException.class, () -> rdsService.createDbInstance(
                "mydb", "postgres", "13", "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, 7, "02:00-02:30", "tue:02:15-tue:02:45", null)));
        assertEquals("The backup window and maintenance window must not overlap.", overlapping.getMessage());

        // a backup window that wraps midnight still collides with a maintenance window on the next day
        AwsException wrapping = assertThrows(AwsException.class, () -> rdsService.createDbInstance(
                "mydb", "postgres", "13", "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, 7, "23:45-00:15", "wed:00:00-wed:00:30", null)));
        assertEquals("The backup window and maintenance window must not overlap.", wrapping.getMessage());

        // across the week boundary in both directions: a Sunday backup window running into Monday
        // against an early-Monday maintenance window, and a Sunday-night maintenance window running
        // into Monday against an early-Monday backup window
        AwsException sundayBackup = assertThrows(AwsException.class, () -> rdsService.createDbInstance(
                "mydb", "postgres", "13", "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, 7, "23:45-00:15", "mon:00:00-mon:00:30", null)));
        assertEquals("The backup window and maintenance window must not overlap.", sundayBackup.getMessage());
        AwsException sundayMaintenance = assertThrows(AwsException.class, () -> rdsService.createDbInstance(
                "mydb", "postgres", "13", "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, 7, "00:00-00:30", "sun:23:45-mon:00:15", null)));
        assertEquals("The backup window and maintenance window must not overlap.", sundayMaintenance.getMessage());
        // and the same shapes a minute apart are clear
        rdsService.createDbInstance("clear", "postgres", "13", "admin", "password", "dbname",
                "db.t3.micro", 20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, 7, "23:45-00:15", "mon:00:15-mon:00:45", null));

        // a window given alone is checked against the one that will be in effect: on create the
        // default, replaced by a window starting where the given one ends rather than refused,
        // since AWS would have picked a random window clear of the given one
        rdsService.createDbInstance("alone", "postgres", "13", "admin", "password", "dbname",
                "db.t3.micro", 20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, null, "00:30-01:00", null, null));
        assertEquals("mon:01:00-mon:01:30", rdsService.getDbInstance("alone").getPreferredMaintenanceWindow());
        // a long daily window that no fixed alternate could be clear of
        rdsService.createDbInstance("long", "postgres", "13", "admin", "password", "dbname",
                "db.t3.micro", 20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, null, "00:00-07:00", null, null));
        assertEquals("mon:07:00-mon:07:30", rdsService.getDbInstance("long").getPreferredMaintenanceWindow());
        // ending near midnight rolls the maintenance window onto the next day
        rdsService.createDbInstance("midnight", "postgres", "13", "admin", "password", "dbname",
                "db.t3.micro", 20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, null, "02:00-23:45", null, null));
        assertEquals("mon:23:45-tue:00:15", rdsService.getDbInstance("midnight").getPreferredMaintenanceWindow());
        rdsService.createDbInstance("alone2", "postgres", "13", "admin", "password", "dbname",
                "db.t3.micro", 20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, null, null, "mon:04:30-mon:05:00", null));
        assertEquals("05:00-05:30", rdsService.getDbInstance("alone2").getPreferredBackupWindow());

        // a window alone that leaves no 30-minute gap for the other kind, and a maintenance window
        // of a day or more — both refused with AWS's wording
        AwsException noRoom = assertThrows(AwsException.class, () -> rdsService.createDbInstance(
                "mydb", "postgres", "13", "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, null, "00:00-23:45", null, null)));
        assertEquals("The specified backup window overlaps all available default maintenance windows. "
                + "Shrink the backup window or specify a non-overlapping maintenance window.", noRoom.getMessage());
        AwsException noRoomForBackup = assertThrows(AwsException.class, () -> rdsService.createDbInstance(
                "mydb", "postgres", "13", "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, null, null, "mon:00:00-mon:23:45", null)));
        assertEquals("The specified maintenance window overlaps all available default backup windows. "
                + "Shrink the maintenance window or specify a non-overlapping backup window.", noRoomForBackup.getMessage());
        AwsException tooLong = assertThrows(AwsException.class, () -> rdsService.createDbInstance(
                "mydb", "postgres", "13", "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, null, null, "mon:00:00-wed:00:00", null)));
        assertEquals("Maintenance window must be less than 24 hours.", tooLong.getMessage());

        // AWS accepted a 40-day retention period, so it is not range-checked here
        rdsService.createDbInstance("mydb", "postgres", "13", "admin", "password", "dbname",
                "db.t3.micro", 20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, 40, "02:00-02:30", "tue:03:00-tue:03:30", null));
        assertEquals(40, rdsService.getDbInstance("mydb").getBackupRetentionPeriod());
    }

    @Test
    void createDbInstanceRejectsMalformedWindows() {
        AwsException backup = assertThrows(AwsException.class, () -> rdsService.createDbInstance(
                "mydb", "postgres", "13", "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, null, "25:00-26:00", null, null)));
        assertEquals("InvalidParameterValue", backup.getErrorCode());

        AwsException maintenance = assertThrows(AwsException.class, () -> rdsService.createDbInstance(
                "mydb", "postgres", "13", "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, null, null, "xxx:00:00-xxx:01:00", null)));
        assertEquals("InvalidParameterValue", maintenance.getErrorCode());
    }

    @Test
    void modifyDbInstanceAppliesGivenSettingsAndKeepsTheRest() {
        knownKey(KEY_ARN);
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(true, "arn:aws:kms:us-east-1:123456789012:key/k1", 7,
                        "23:30-00:00", "sun:03:08-sun:03:38", false));

        rdsService.modifyDbInstance("mydb", null, null, null, List.of(), null, null, null,
                new DbInstanceSettings(null, null, 3, "01:00-01:30", null, true));

        DbInstance stored = rdsService.getDbInstance("mydb");
        assertEquals(3, stored.getBackupRetentionPeriod());
        assertEquals("01:00-01:30", stored.getPreferredBackupWindow());
        assertTrue(stored.isCopyTagsToSnapshot());
        assertTrue(stored.isStorageEncrypted());
        assertEquals("arn:aws:kms:us-east-1:123456789012:key/k1", stored.getKmsKeyId());
        assertEquals("sun:03:08-sun:03:38", stored.getPreferredMaintenanceWindow());
    }

    @Test
    void modifyDbInstanceChecksAWindowAgainstTheStoredOther() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, 7, "01:00-01:30", "thu:10:00-thu:10:30", null));

        AwsException maintenance = assertThrows(AwsException.class, () -> rdsService.modifyDbInstance(
                "mydb", null, null, null, List.of(), null, null, null,
                new DbInstanceSettings(null, null, null, null, "mon:01:15-mon:01:45", null)));
        assertEquals("The backup window and maintenance window must not overlap.", maintenance.getMessage());
        AwsException backup = assertThrows(AwsException.class, () -> rdsService.modifyDbInstance(
                "mydb", null, null, null, List.of(), null, null, null,
                new DbInstanceSettings(null, null, null, "10:15-10:45", null, null)));
        assertEquals("The backup window and maintenance window must not overlap.", backup.getMessage());
        assertEquals("01:00-01:30", rdsService.getDbInstance("mydb").getPreferredBackupWindow());
        assertEquals("thu:10:00-thu:10:30", rdsService.getDbInstance("mydb").getPreferredMaintenanceWindow());

        // both replaced at once: only the new pair has to be clear of each other
        rdsService.modifyDbInstance("mydb", null, null, null, List.of(), null, null, null,
                new DbInstanceSettings(null, null, null, "10:00-10:30", "mon:01:00-mon:01:30", null));
        assertEquals("10:00-10:30", rdsService.getDbInstance("mydb").getPreferredBackupWindow());
        assertEquals("mon:01:00-mon:01:30", rdsService.getDbInstance("mydb").getPreferredMaintenanceWindow());
    }

    @Test
    void modifyDbInstanceRejectsInvalidSettingsWithoutChangingTheRecord() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, 7, null, null, null));

        assertThrows(AwsException.class, () -> rdsService.modifyDbInstance(
                "mydb", null, null, null, List.of(), null, null, null,
                new DbInstanceSettings(null, null, null, "bad", null, null)));

        assertEquals(7, rdsService.getDbInstance("mydb").getBackupRetentionPeriod());
    }

    @Test
    void modifyDbInstanceCannotWriteAnInstanceBackAfterDelete() throws Exception {
        // The interleaving this guards against: modify has read the instance, delete removes it,
        // modify writes its copy back. The store is held inside modify's put so the delete can be
        // run in that window, rather than hoping a loop hits it.
        PausingStorageBackend<DbInstance> instances = new PausingStorageBackend<>(new InMemoryStorage<>());
        RdsService service = newService(containerManager, proxyManager, instances,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        service.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);

        instances.pauseOn(PausingStorageBackend.Call.PUT, "::mydb");
        java.util.concurrent.atomic.AtomicReference<Throwable> modifyOutcome = new java.util.concurrent.atomic.AtomicReference<>();
        Thread modify = new Thread(() -> {
            try {
                service.modifyDbInstance("mydb", null, null, null, List.of(), null, null, null,
                        new DbInstanceSettings(null, null, 3, null, null, null));
            } catch (Throwable t) {
                modifyOutcome.set(t);
            }
        });
        modify.start();
        instances.awaitReached();

        Thread delete = new Thread(() -> service.deleteDbInstance("mydb"));
        delete.start();
        // guarded: the delete queues on the monitor modify holds; unguarded: it runs to completion
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (delete.getState() != Thread.State.BLOCKED && delete.getState() != Thread.State.TERMINATED) {
            assertTrue(System.nanoTime() < deadline, "delete neither ran nor queued");
            Thread.onSpinWait();
        }
        instances.release();
        modify.join(5000);
        delete.join(5000);

        assertNull(modifyOutcome.get(), "modify completed before the delete");
        assertThrows(AwsException.class, () -> service.getDbInstance("mydb"),
                "the deleted instance must not come back from the modify");
    }
}

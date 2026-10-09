package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheMemcachedContainerManager;
import io.github.hectorvent.floci.services.elasticache.container.ValkeyClusterFormation;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheAuthProxy;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import io.github.hectorvent.floci.services.kms.KmsService;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ElastiCacheServerlessServiceTest {
    private ElastiCacheServerlessService service;
    private ElastiCacheService runtime;
    private ElastiCacheMemcachedService memcached;
    private ElastiCacheContainerManager containers;
    private ElastiCacheProxyManager proxies;
    private StorageFactory factory;
    private EmulatorConfig config;
    private RegionResolver resolver;
    private Ec2Service ec2;
    private KmsService kms;
    private RequestContext context;
    @TempDir
    Path directory;

    @BeforeEach
    void setUp() {
        factory = mock(StorageFactory.class);
        context = new RequestContext();
        context.setAccountId("000000000000");
        context.setRegion("us-east-1");
        Instance<RequestContext> requestInstance = mock(Instance.class);
        when(requestInstance.get()).thenReturn(context);
        Map<String, AccountAwareStorageBackend<?>> stores = new ConcurrentHashMap<>();
        when(factory.create(anyString(), anyString(), any())).thenAnswer(invocation -> stores.computeIfAbsent(
                invocation.getArgument(1), file -> new AccountAwareStorageBackend<>(new InMemoryStorage<>(), requestInstance, "000000000000")));
        config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig settings = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.elasticache()).thenReturn(settings);
        when(settings.proxyBasePort()).thenReturn(17400);
        when(settings.proxyMaxPort()).thenReturn(17401);
        when(settings.defaultImage()).thenReturn("valkey/valkey:8");
        when(settings.defaultMemcachedImage()).thenReturn("memcached:1.6");
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        resolver = spy(new RegionResolver("us-east-1", "000000000000"));
        doAnswer(invocation -> context.getAccountId()).when(resolver).getAccountId();
        doAnswer(invocation -> context.getRegion()).when(resolver).getRegion();
        containers = mock(ElastiCacheContainerManager.class);
        when(containers.tryStart(anyString(), anyString(), any())).thenReturn(
                new ElastiCacheContainerHandle("container", "backing", "localhost", 6379));
        proxies = mock(ElastiCacheProxyManager.class);
        ec2 = mock(Ec2Service.class);
        when(ec2.describeSubnets(anyString(), anyList(), anyMap())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(1);
            return ids.stream().filter(id -> id.equals("subnet-local")).map(id -> {
                Subnet subnet = new Subnet();
                subnet.setSubnetId(id);
                subnet.setVpcId("vpc-local");
                return subnet;
            }).toList();
        });
        when(ec2.describeSecurityGroups(anyString(), anyList(), anyList(), anyMap())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(1);
            return ids.stream().filter(id -> id.equals("sg-local") || id.equals("sg-new")).map(id -> {
                SecurityGroup group = new SecurityGroup();
                group.setGroupId(id);
                group.setVpcId("vpc-local");
                return group;
            }).toList();
        });
        kms = mock(KmsService.class);
        when(kms.describeKey(anyString(), anyString())).thenThrow(new AwsException("NotFoundException", "Missing key", 400));
        runtime = new ElastiCacheService(containers, proxies, mock(ValkeyClusterFormation.class), factory,
                config, ec2, resolver, kms, new ElastiCacheProvisioningIds());
        memcached = new ElastiCacheMemcachedService(mock(ElastiCacheMemcachedContainerManager.class), factory,
                config, new ElastiCacheProvisioningIds(), resolver);
        service = new ElastiCacheServerlessService(factory, resolver, runtime, memcached, runtime);
        runtime.createUser("alice", "alice", AuthMode.PASSWORD, List.of("password-a"), "on ~* +@all", "valkey");
        runtime.createUser("bob", "bob", AuthMode.PASSWORD, List.of("password-b"), "on ~* +@all", "valkey");
        runtime.createUserGroup("team", "valkey", List.of("alice"), "us-east-1");
    }

    private ElastiCacheServerlessService.CreateServerlessCacheRequest create(String name, String engine, String group) {
        return new ElastiCacheServerlessService.CreateServerlessCacheRequest(name, engine, null, "description",
                List.of("subnet-local"), List.of("sg-local"), group, null, null, 0, null, List.of(),
                Map.of("team", "local"));
    }

    private ElastiCacheServerlessService.ModifyServerlessCacheRequest modify(String name, String group, Boolean remove) {
        return new ElastiCacheServerlessService.ModifyServerlessCacheRequest(name, "updated", null, null,
                List.of("sg-new"), group, remove, null, null, null);
    }

    private void error(String code, Runnable action) {
        assertEquals(code, assertThrows(AwsException.class, action::run).getErrorCode());
    }

    @Test
    void createsHiddenRuntimeMutatesMetadataAndReleasesPortOnDelete() {
        ServerlessCache cache = service.createServerlessCache(create("cache", "valkey", "team"));
        assertEquals("available", cache.getStatus());
        assertEquals(cache.getEndpoint(), cache.getReaderEndpoint());
        assertTrue(runtime.findCacheClusters(null).isEmpty());
        error("CacheClusterNotFound", () -> runtime.deleteCacheCluster(cache.getBackingCacheClusterId()));
        error("InvalidUserGroupState", () -> runtime.deleteUserGroup("team"));
        ServerlessCache updated = service.modifyServerlessCache(modify("cache", null, null));
        assertEquals(cache.getEndpoint(), updated.getEndpoint());
        assertEquals(cache.getBackingCacheClusterId(), updated.getBackingCacheClusterId());
        assertEquals("updated", updated.getDescription());
        assertEquals(List.of("sg-new"), updated.getSecurityGroupIds());
        error("ServerlessCacheAlreadyExistsFault", () -> service.createServerlessCache(create("cache", "valkey", "team")));
        assertEquals(List.of("cache"), List.copyOf(runtime.getUserGroup("team").getServerlessCacheEngines().keySet()));
        service.deleteServerlessCache("cache");
        assertTrue(service.describeServerlessCaches(null).isEmpty());
        runtime.deleteUserGroup("team");
        ServerlessCache replacement = service.createServerlessCache(create("replacement", "valkey", null));
        assertEquals(cache.getEndpoint().port(), replacement.getEndpoint().port());
    }

    @Test
    void replaceTagsDrivesTagsToExactlyTheGivenSet() {
        service.createServerlessCache(create("tagged", "valkey", null));
        service.replaceTags("tagged", Map.of("a", "1", "b", "2"));
        assertEquals(Map.of("a", "1", "b", "2"), service.getServerlessCache("tagged").getTags());
        service.replaceTags("tagged", Map.of("b", "3"));
        assertEquals(Map.of("b", "3"), service.getServerlessCache("tagged").getTags());
        service.replaceTags("tagged", Map.of());
        assertTrue(service.getServerlessCache("tagged").getTags().isEmpty());
    }

    @Test
    void credentialsReadLiveMembershipAndUpdatedUserGroup() {
        ServerlessCache cache = service.createServerlessCache(create("cache", "valkey", "team"));
        ArgumentCaptor<ElastiCacheAuthProxy.PasswordValidator> validator = ArgumentCaptor.forClass(
                ElastiCacheAuthProxy.PasswordValidator.class);
        verify(proxies).startProxy(eq(cache.getBackingCacheClusterId()), any(), anyInt(), anyString(), anyInt(),
                validator.capture(), eq("cache"));
        assertTrue(validator.getValue().validatePassword("alice", "password-a"));
        assertFalse(validator.getValue().validatePassword("bob", "password-b"));
        runtime.modifyUserGroup("team", List.of("bob"), List.of("alice"), null);
        assertFalse(validator.getValue().validatePassword("alice", "password-a"));
        assertTrue(validator.getValue().validatePassword("bob", "password-b"));
        runtime.modifyUser("bob", AuthMode.PASSWORD, List.of("password-b-updated"), null, null, null);
        assertFalse(validator.getValue().validatePassword("bob", "password-b"));
        assertTrue(validator.getValue().validatePassword("bob", "password-b-updated"));
        runtime.createUserGroup("other", "valkey", List.of("alice"), "us-east-1");
        service.modifyServerlessCache(modify("cache", "other", false));
        assertTrue(validator.getValue().validatePassword("alice", "password-a"));
        assertFalse(validator.getValue().validatePassword("bob", "password-b"));
        assertTrue(List.copyOf(runtime.getUserGroup("team").getServerlessCacheEngines().keySet()).isEmpty());
        service.modifyServerlessCache(modify("cache", null, true));
        assertFalse(validator.getValue().hasMembers());
    }

    @Test
    void validationAndFailedAllocationLeaveNoAssociationOrRecord() {
        error("UserGroupNotFound", () -> service.createServerlessCache(create("missing", "valkey", "absent")));
        error("InvalidParameterCombination", () -> service.createServerlessCache(create("memcached", "memcached", "team")));
        error("InvalidParameterValue", () -> service.createServerlessCache(create("bad_name", "valkey", null)));
        verifyNoInteractions(proxies);
        when(containers.tryStart(anyString(), anyString(), any())).thenThrow(new IllegalStateException("allocation failed"));
        assertThrows(IllegalStateException.class, () -> service.createServerlessCache(create("failed", "valkey", "team")));
        assertTrue(List.copyOf(runtime.getUserGroup("team").getServerlessCacheEngines().keySet()).isEmpty());
        assertTrue(service.describeServerlessCaches(null).isEmpty());
        assertTrue(runtime.findCacheClusters(null).isEmpty());
    }

    @Test
    void failedBackingCleanupStillDetachesTheUserGroupAfterAFailedCreate() {
        IllegalStateException creationFailure = new IllegalStateException("record failed");
        doThrow(creationFailure).when(resolver).buildArn("elasticache", "us-east-1", "serverlesscache:failed");
        doThrow(new IllegalStateException("stop failed")).when(proxies).stopProxy(anyString());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.createServerlessCache(create("failed", "valkey", "team")));

        assertSame(creationFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(runtime.getUserGroup("team").getServerlessCacheEngines().isEmpty());
    }

    @Test
    void failedDeletionKeepsGroupProtectedUntilRetrySucceeds() {
        ServerlessCache cache = service.createServerlessCache(create("cache", "valkey", "team"));
        doThrow(new IllegalStateException("stop failed")).when(proxies).stopProxy(cache.getBackingCacheClusterId());
        assertThrows(IllegalStateException.class, () -> service.deleteServerlessCache("cache"));
        error("InvalidUserGroupState", () -> runtime.deleteUserGroup("team"));
        doNothing().when(proxies).stopProxy(cache.getBackingCacheClusterId());
        service.deleteServerlessCache("cache");
        runtime.deleteUserGroup("team");
    }

    @Test
    void rehydratedServiceRetainsRuntimeIdentityAndAssociation() {
        ServerlessCache original = service.createServerlessCache(create("cache", "valkey", "team"));
        ElastiCacheServerlessService reloaded = new ElastiCacheServerlessService(factory, resolver, runtime, memcached, runtime);
        assertEquals(original.getBackingCacheClusterId(), reloaded.getServerlessCache("cache").getBackingCacheClusterId());
        error("InvalidUserGroupState", () -> runtime.deleteUserGroup("team"));
        reloaded.deleteServerlessCache("cache");
        runtime.deleteUserGroup("team");
    }

    @Test
    void emptyAttachedGroupRemainsAuthRequiredAndCannotAuthenticate() {
        runtime.createUserGroup("empty", "valkey", List.of(), "us-east-1");
        ServerlessCache cache = service.createServerlessCache(create("empty-cache", "valkey", "empty"));
        ArgumentCaptor<ElastiCacheAuthProxy.PasswordValidator> validator = ArgumentCaptor.forClass(
                ElastiCacheAuthProxy.PasswordValidator.class);
        verify(proxies).startProxy(eq(cache.getBackingCacheClusterId()), any(), anyInt(), anyString(), anyInt(),
                validator.capture(), eq("empty-cache"));
        assertTrue(validator.getValue().hasMembers());
        assertNull(validator.getValue().memberAuthMode("default"));
        assertFalse(validator.getValue().validatePassword(null, "password-a"));
    }

    @Test
    void concurrentDuplicateCannotRemoveWinningAssociation() throws Exception {
        CountDownLatch allocating = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        when(containers.tryStart(anyString(), anyString(), any())).thenAnswer(invocation -> {
            allocating.countDown();
            assertTrue(complete.await(10, TimeUnit.SECONDS));
            return new ElastiCacheContainerHandle("container", "backing", "localhost", 6379);
        });
        CompletableFuture<ServerlessCache> first = CompletableFuture.supplyAsync(() ->
                service.createServerlessCache(create("race", "valkey", "team")));
        assertTrue(allocating.await(10, TimeUnit.SECONDS));
        error("InvalidUserGroupState", () -> runtime.deleteUserGroup("team"));
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
            AwsException exception = assertThrows(AwsException.class,
                    () -> service.createServerlessCache(create("race", "valkey", "team")));
            return exception.getErrorCode();
        });
        complete.countDown();
        assertEquals("race", first.get(10, TimeUnit.SECONDS).getServerlessCacheName());
        assertEquals("ServerlessCacheAlreadyExistsFault", second.get(10, TimeUnit.SECONDS));
        assertEquals(List.of("race"), List.copyOf(runtime.getUserGroup("team").getServerlessCacheEngines().keySet()));
    }

    @Test
    void snapshotsAndUnsupportedTransitionsFailWithoutMutation() {
        ElastiCacheServerlessService.CreateServerlessCacheRequest base = create("snapshot", "valkey", "team");
        error("InvalidParameterValue", () -> service.createServerlessCache(new ElastiCacheServerlessService.CreateServerlessCacheRequest(
                base.serverlessCacheName(), base.engine(), null, null, null, null, "team", null, null, null, null,
                List.of("arn:aws:elasticache:us-east-1:000000000000:serverlesscachesnapshot:test"), null)));
        assertTrue(List.copyOf(runtime.getUserGroup("team").getServerlessCacheEngines().keySet()).isEmpty());
        service.createServerlessCache(base);
        error("InvalidParameterValue", () -> service.deleteServerlessCache("snapshot", "final-snapshot"));
        error("InvalidParameterValue", () -> service.modifyServerlessCache(new ElastiCacheServerlessService.ModifyServerlessCacheRequest(
                "snapshot", "must-not-change", "redis", null, null, null, null, null, null, null)));
        error("UserGroupNotFound", () -> service.modifyServerlessCache(modify("snapshot", "absent", false)));
        assertEquals("description", service.getServerlessCache("snapshot").getDescription());
        assertEquals("team", service.getServerlessCache("snapshot").getUserGroupId());
    }

    @Test
    void persistentRestartRestoresHiddenRuntimeCredentialsAndPortOwnership() {
        StorageFactory persistent = persistentFactory();
        ElastiCacheService persistedRuntime = newRuntime(persistent);
        ElastiCacheServerlessService persisted = new ElastiCacheServerlessService(persistent, resolver, persistedRuntime, memcached, persistedRuntime);
        persistedRuntime.createUser("member", "member", AuthMode.PASSWORD, List.of("secret"), "on ~* +@all", "valkey");
        persistedRuntime.createUserGroup("persisted-team", "valkey", List.of("member"), "us-east-1");
        ServerlessCache original = persisted.createServerlessCache(create("persistent", "valkey", "persisted-team"));
        persistent.shutdownAll();
        StorageFactory reloadedFactory = persistentFactory();
        ElastiCacheService reloadedRuntime = newRuntime(reloadedFactory);
        ElastiCacheServerlessService reloaded = new ElastiCacheServerlessService(reloadedFactory, resolver, reloadedRuntime, memcached, reloadedRuntime);
        reloadedFactory.loadAll();
        clearInvocations(proxies);
        reloadedRuntime.restorePersistedRuntime().join();
        ServerlessCache restored = reloaded.getServerlessCache("persistent");
        assertEquals(original.getBackingCacheClusterId(), restored.getBackingCacheClusterId());
        assertEquals(original.getEndpoint(), restored.getEndpoint());
        assertEquals("000000000000", restored.getAccountId());
        assertTrue(reloadedRuntime.findCacheClusters(null).isEmpty());
        ArgumentCaptor<ElastiCacheAuthProxy.PasswordValidator> validator = ArgumentCaptor.forClass(
                ElastiCacheAuthProxy.PasswordValidator.class);
        verify(proxies).startProxy(eq(restored.getBackingCacheClusterId()), any(), anyInt(), anyString(), anyInt(),
                validator.capture(), eq("persistent"));
        assertTrue(validator.getValue().validatePassword("member", "secret"));
        assertEquals(List.of("persistent"), List.copyOf(reloadedRuntime.getUserGroup("persisted-team").getServerlessCacheEngines().keySet()));
        reloaded.deleteServerlessCache("persistent");
        reloadedRuntime.deleteUserGroup("persisted-team");
        ServerlessCache replacement = reloaded.createServerlessCache(create("reused", "valkey", null));
        assertEquals(original.getEndpoint().port(), replacement.getEndpoint().port());
        reloaded.deleteServerlessCache("reused");
        reloadedFactory.shutdownAll();
    }

    @Test
    void credentialsAndRestoreUseRecordedOwnerOutsideRequestScope() {
        context.setAccountId("111111111111");
        context.setRegion("cn-north-1");
        runtime.createUser("alice", "alice", AuthMode.PASSWORD, List.of("owner-secret"), "on ~* +@all", "valkey");
        runtime.createUserGroup("team", "valkey", List.of("alice"), "us-east-1");
        ServerlessCache original = service.createServerlessCache(create("account-cache", "valkey", "team"));
        assertTrue(original.getArn().startsWith("arn:aws-cn:elasticache:cn-north-1:111111111111:"));
        context.setAccountId("000000000000");
        context.setRegion("us-east-1");
        assertTrue(service.describeServerlessCaches(null).isEmpty());
        ElastiCacheService reloadedRuntime = newRuntime(factory);
        clearInvocations(proxies);
        reloadedRuntime.restorePersistedRuntime().join();
        ArgumentCaptor<ElastiCacheAuthProxy.PasswordValidator> validator = ArgumentCaptor.forClass(
                ElastiCacheAuthProxy.PasswordValidator.class);
        verify(proxies).startProxy(eq(original.getBackingCacheClusterId()), any(), anyInt(), anyString(), anyInt(),
                validator.capture(), eq("account-cache"));
        assertTrue(validator.getValue().validatePassword("alice", "owner-secret"));
        assertFalse(validator.getValue().validatePassword("alice", "password-a"));
        AccountAwareStorageBackend<CacheCluster> backing = factory.create("elasticache", "elasticache-redis-clusters.json", null);
        assertTrue(backing.getForAccount("000000000000", original.getBackingCacheClusterId()).isEmpty());
        assertTrue(backing.getForAccount("111111111111", original.getBackingCacheClusterId()).isPresent());
        context.setAccountId("111111111111");
        context.setRegion("cn-north-1");
        ElastiCacheServerlessService reloaded = new ElastiCacheServerlessService(factory, resolver, reloadedRuntime, memcached, reloadedRuntime);
        assertEquals(original.getEndpoint(), reloaded.getServerlessCache("account-cache").getEndpoint());
        reloaded.deleteServerlessCache("account-cache");
        runtime.deleteUserGroup("team");
    }

    private ElastiCacheService newRuntime(StorageFactory activeFactory) {
        return new ElastiCacheService(containers, proxies, mock(ValkeyClusterFormation.class), activeFactory,
                config, ec2, resolver, kms, new ElastiCacheProvisioningIds());
    }

    @Test
    void missingNetworkAndKmsDependenciesAreRejectedBeforeRuntimeAllocation() {
        for (ElastiCacheServerlessService.CreateServerlessCacheRequest request : List.of(
                new ElastiCacheServerlessService.CreateServerlessCacheRequest("missing-subnet", "valkey", null,
                        null, List.of("subnet-absent"), null, "team", null, null, null, null, null, null),
                new ElastiCacheServerlessService.CreateServerlessCacheRequest("missing-sg", "valkey", null,
                        null, null, List.of("sg-absent"), "team", null, null, null, null, null, null),
                new ElastiCacheServerlessService.CreateServerlessCacheRequest("missing-key", "valkey", null,
                        null, null, null, "team", "missing", null, null, null, null, null))) {
            error("InvalidParameterValue", () -> service.createServerlessCache(request));
        }
        verifyNoInteractions(proxies);
        assertTrue(List.copyOf(runtime.getUserGroup("team").getServerlessCacheEngines().keySet()).isEmpty());
        assertTrue(service.describeServerlessCaches(null).isEmpty());
    }

    private StorageFactory persistentFactory() {
        EmulatorConfig persistenceConfig = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(persistenceConfig.defaultAccountId()).thenReturn("000000000000");
        when(persistenceConfig.storage().persistentPath()).thenReturn(directory.toString());
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn("persistent");
        when(access.storageFlushInterval(anyString())).thenReturn(60_000L);
        return new StorageFactory(persistenceConfig, access);
    }
}

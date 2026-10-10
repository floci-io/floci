package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.container.ValkeyClusterFormation;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheAuthProxy;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import io.github.hectorvent.floci.services.kms.KmsService;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ElastiCacheRestoreReviewTest {
    private final AtomicReference<String> account = new AtomicReference<>("000000000000");
    private final Map<String, AccountAwareStorageBackend<?>> stores = new ConcurrentHashMap<>();
    private final ElastiCacheProxyManager proxies = mock(ElastiCacheProxyManager.class);

    @SuppressWarnings("unchecked")
    private ElastiCacheService service() {
        RequestContext context = mock(RequestContext.class);
        when(context.getAccountId()).thenAnswer(inv -> account.get());
        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenReturn(context);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any())).thenAnswer(inv ->
                stores.computeIfAbsent(inv.getArgument(1), ignored ->
                        new AccountAwareStorageBackend<>(new InMemoryStorage<>(), contexts, "000000000000")));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().elasticache().proxyBasePort()).thenReturn(16379);
        when(config.services().elasticache().proxyMaxPort()).thenReturn(16399);
        when(config.services().elasticache().defaultImage()).thenReturn("valkey/valkey:8");
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        ElastiCacheContainerManager containers = mock(ElastiCacheContainerManager.class);
        when(containers.tryStart(anyString(), anyString(), any())).thenReturn(
                new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379));
        return new ElastiCacheService(containers, proxies, mock(ValkeyClusterFormation.class),
                factory, config, mock(Ec2Service.class), new RegionResolver("us-east-1", "000000000000"),
                mock(KmsService.class), new ElastiCacheProvisioningIds());
    }

    @SuppressWarnings("unchecked")
    private void seed(String owner, int port) {
        ReplicationGroup group = new ReplicationGroup();
        group.setReplicationGroupId("grp");
        group.setStatus(ReplicationGroupStatus.AVAILABLE);
        group.setProxyPort(port);
        group.setAuthMode(AuthMode.PASSWORD);
        group.setAuthToken("secret-token");
        AccountAwareStorageBackend<ReplicationGroup> groups =
                (AccountAwareStorageBackend<ReplicationGroup>) stores.get("elasticache-groups.json");
        groups.putForAccount(owner, "grp", group);
    }

    @Test
    void restoredForeignAccountAcceptsItsPassword() {
        ElastiCacheService service = service();
        seed("111111111111", 16379);
        service.restorePersistedRuntime().join();
        ArgumentCaptor<ElastiCacheAuthProxy.PasswordValidator> validator =
                ArgumentCaptor.forClass(ElastiCacheAuthProxy.PasswordValidator.class);
        verify(proxies).startProxy(eq("grp"), eq(AuthMode.PASSWORD), eq(16379), anyString(), anyInt(),
                validator.capture());
        assertTrue(validator.getValue().validatePassword(null, "secret-token"));
    }

    @Test
    void deletingBothRestoredAccountsReleasesBothPorts() {
        ElastiCacheService service = service();
        seed("000000000000", 16379);
        seed("111111111111", 16380);
        service.restorePersistedRuntime().join();
        service.deleteReplicationGroup("grp");
        account.set("111111111111");
        service.deleteReplicationGroup("grp");
        assertEquals(16379, service.createReplicationGroup("new1", "test", AuthMode.NO_AUTH, null,
                "us-east-1").getProxyPort());
        assertEquals(16380, service.createReplicationGroup("new2", "test", AuthMode.NO_AUTH, null,
                "us-east-1").getProxyPort());
    }

    @Test
    @SuppressWarnings("unchecked")
    void restoredForeignAccountCacheClusterAcceptsItsPassword() {
        ElastiCacheService service = service();
        CacheCluster cluster = new CacheCluster();
        cluster.setCacheClusterId("cc");
        cluster.setCacheClusterStatus(CacheClusterStatus.AVAILABLE);
        cluster.setConfigurationEndpoint(new Endpoint("localhost", 16379));
        cluster.setAuthMode(AuthMode.PASSWORD);
        cluster.setAuthToken("secret-token");
        ((AccountAwareStorageBackend<CacheCluster>) stores.get("elasticache-redis-clusters.json"))
                .putForAccount("111111111111", "cc", cluster);
        service.restorePersistedRuntime().join();
        ArgumentCaptor<ElastiCacheAuthProxy.PasswordValidator> validator =
                ArgumentCaptor.forClass(ElastiCacheAuthProxy.PasswordValidator.class);
        verify(proxies).startProxy(eq("cc"), eq(AuthMode.PASSWORD), eq(16379), anyString(), anyInt(),
                validator.capture());
        assertTrue(validator.getValue().validatePassword(null, "secret-token"));
    }

    @Test
    void groupCreatedUnderAnotherAccountAcceptsItsPasswordOutsideTheRequest() {
        ElastiCacheService service = service();
        account.set("111111111111");
        service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, "secret-token", "us-east-1");
        ArgumentCaptor<ElastiCacheAuthProxy.PasswordValidator> validator =
                ArgumentCaptor.forClass(ElastiCacheAuthProxy.PasswordValidator.class);
        verify(proxies).startProxy(eq("grp"), eq(AuthMode.PASSWORD), anyInt(), anyString(), anyInt(),
                validator.capture());
        // The proxy authenticates on its own thread, where the store falls back to the default account.
        account.set("000000000000");
        assertTrue(validator.getValue().validatePassword(null, "secret-token"));
    }

    @Test
    void userGroupMemberOfAnotherAccountAuthenticatesOutsideTheRequest() {
        ElastiCacheService service = service();
        account.set("111111111111");
        service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null, "us-east-1");
        service.createUser("default-user-id", "default", AuthMode.PASSWORD, List.of("default-pass"),
                "on ~* +@all", null);
        service.createUser("app-user-id", "app", AuthMode.PASSWORD, List.of("app-pass"), "on ~* +@all", null);
        service.createUserGroup("app-group", "redis", List.of("default-user-id", "app-user-id"), "us-east-1");
        service.modifyReplicationGroup("grp", List.of("app-group"), null);
        ArgumentCaptor<ElastiCacheAuthProxy.PasswordValidator> validator =
                ArgumentCaptor.forClass(ElastiCacheAuthProxy.PasswordValidator.class);
        verify(proxies, atLeastOnce()).startProxy(eq("grp"), any(), anyInt(), anyString(), anyInt(),
                validator.capture());
        // The proxy authenticates on its own thread, where the store falls back to the default account.
        account.set("000000000000");
        assertTrue(validator.getValue().validatePassword("app", "app-pass"));
    }

    @Test
    void cacheClusterCreatedUnderAnotherAccountAcceptsItsPasswordOutsideTheRequest() {
        ElastiCacheService service = service();
        account.set("111111111111");
        service.createCacheCluster(new ElastiCacheService.CreateCacheClusterRequest("cc", "redis", null,
                null, null, null, AuthMode.PASSWORD, "secret-token", null, null, null, null, null, null,
                null, null, null, null, "us-east-1", null));
        ArgumentCaptor<ElastiCacheAuthProxy.PasswordValidator> validator =
                ArgumentCaptor.forClass(ElastiCacheAuthProxy.PasswordValidator.class);
        verify(proxies).startProxy(eq("cc"), eq(AuthMode.PASSWORD), anyInt(), anyString(), anyInt(),
                validator.capture());
        // The proxy authenticates on its own thread, where the store falls back to the default account.
        account.set("000000000000");
        assertTrue(validator.getValue().validatePassword(null, "secret-token"));
    }
}

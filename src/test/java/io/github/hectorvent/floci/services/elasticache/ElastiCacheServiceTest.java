package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElastiCacheServiceTest {

    private ElastiCacheService service;

    @BeforeEach
    void setUp() {
        ElastiCacheContainerManager containerManager = mock(ElastiCacheContainerManager.class);
        ElastiCacheProxyManager proxyManager = mock(ElastiCacheProxyManager.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        EmulatorConfig config = mock(EmulatorConfig.class);

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.proxyBasePort()).thenReturn(16379);
        when(ecConfig.proxyMaxPort()).thenReturn(16399);
        when(ecConfig.defaultImage()).thenReturn("valkey/valkey:8");
        when(config.hostname()).thenReturn(java.util.Optional.of("localhost"));

        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> new InMemoryStorage<>());
        when(containerManager.start(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379));
        doNothing().when(proxyManager).startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());

        service = new ElastiCacheService(containerManager, proxyManager, storageFactory, config);
    }

    @Test
    void singleArgAuthMatchesDefaultUserOnly() {
        service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null);

        service.createUser("default-user-id", "default", AuthMode.PASSWORD,
                List.of("default-pass"), "on ~* +@all");
        service.createUser("other-user-id", "other", AuthMode.PASSWORD,
                List.of("other-pass"), "on ~* +@all");

        service.modifyReplicationGroup("grp",
                List.of("default-user-id", "other-user-id"), null);

        // Single-arg AUTH with default user's password should succeed
        assertTrue(service.validatePassword("grp", null, "default-pass"));

        // Single-arg AUTH with other user's password should fail
        assertFalse(service.validatePassword("grp", null, "other-pass"),
                "AUTH <password> must only match the 'default' user per Redis 6+ ACL spec");
    }

    @Test
    void twoArgAuthMatchesNamedUser() {
        service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null);

        service.createUser("other-user-id", "other", AuthMode.PASSWORD,
                List.of("other-pass"), "on ~* +@all");

        service.modifyReplicationGroup("grp", List.of("other-user-id"), null);

        // Two-arg AUTH with correct username + password should succeed
        assertTrue(service.validatePassword("grp", "other", "other-pass"));

        // Two-arg AUTH with wrong username should fail
        assertFalse(service.validatePassword("grp", "wrong", "other-pass"));
    }

    @Test
    void singleArgAuthFallsBackToGroupAuthToken() {
        service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, "group-token");

        // Single-arg AUTH with group auth token should succeed
        assertTrue(service.validatePassword("grp", null, "group-token"));

        // Single-arg AUTH with wrong password should fail
        assertFalse(service.validatePassword("grp", null, "wrong-token"));
    }
}

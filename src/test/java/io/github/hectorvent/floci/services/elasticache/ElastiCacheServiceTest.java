package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElastiCacheServiceTest {

    private ElastiCacheService service;
    private ElastiCacheContainerManager containerManager;
    private ElastiCacheProxyManager proxyManager;

    @BeforeEach
    void setUp() {
        containerManager = mock(ElastiCacheContainerManager.class);
        proxyManager = mock(ElastiCacheProxyManager.class);
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

        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        when(containerManager.start(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379));
        doNothing().when(proxyManager).startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());

        Ec2Service ec2Service = org.mockito.Mockito.mock(Ec2Service.class);
        service = new ElastiCacheService(containerManager, proxyManager, storageFactory, config,
                ec2Service, new RegionResolver("us-east-1", "000000000000"));
    }

    @Test
    void singleArgAuthMatchesDefaultUserOnly() {
        service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null);

        service.createUser("default-user-id", "default", AuthMode.PASSWORD,
                List.of("default-pass"), "on ~* +@all", null);
        service.createUser("other-user-id", "other", AuthMode.PASSWORD,
                List.of("other-pass"), "on ~* +@all", null);

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
                List.of("other-pass"), "on ~* +@all", null);

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

    @Test
    void failedProvisioningRollsBackContainerAndReleasesProxyPort() {
        ElastiCacheContainerHandle handle =
                new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379);
        when(containerManager.start(anyString(), anyString())).thenReturn(handle);

        // Proxy startup blows up after the port is reserved and the container is started.
        doThrow(new RuntimeException("proxy boom"))
                .when(proxyManager).startProxy(eq("grp"), any(), anyInt(), anyString(), anyInt(), any());

        // The original failure must propagate to the caller (we clean up, then rethrow).
        assertThrows(RuntimeException.class,
                () -> service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null));

        // Rollback stops by the exact handle, not a fresh by-id lookup.
        verify(proxyManager).stopProxy("grp");
        verify(containerManager).stop(handle);
        verify(containerManager, never()).stopByGroupId(anyString());

        // The reserved proxy port was released: a subsequent successful create reuses the base port
        // instead of skipping to the next one (which is what a leak would cause).
        doNothing().when(proxyManager)
                .startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());
        ReplicationGroup recovered =
                service.createReplicationGroup("grp2", "test", AuthMode.PASSWORD, null);
        assertEquals(16379, recovered.getProxyPort(),
                "Port from the failed create must be released so the next group reuses it");
    }

    @Test
    void failedContainerStartupCleansUpContainerByIdAndReleasesPort() {
        // Models a readiness timeout: start() throws without ever returning a handle.
        doThrow(new RuntimeException("readiness boom"))
                .when(containerManager).start(eq("grp"), anyString());

        assertThrows(RuntimeException.class,
                () -> service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null));

        verify(proxyManager, never()).stopProxy(anyString());
        verify(containerManager).stopByGroupId("grp");

        // The reserved proxy port was still released: a subsequent successful create reuses the base port.
        when(containerManager.start(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "grp2", "localhost", 6379));
        ReplicationGroup recovered =
                service.createReplicationGroup("grp2", "test", AuthMode.PASSWORD, null);
        assertEquals(16379, recovered.getProxyPort(),
                "Port from the failed create must be released so the next group reuses it");
    }

    @Test
    void concurrentCreateForSameGroupIdIsRejectedWhileFirstIsProvisioning() throws InterruptedException {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        when(containerManager.start(anyString(), anyString())).thenAnswer(inv -> {
            startedLatch.countDown();
            assertTrue(releaseLatch.await(5, TimeUnit.SECONDS), "test timed out waiting for release");
            return new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379);
        });

        Thread firstRequest = new Thread(() ->
                service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null));
        firstRequest.start();
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "first request never reached container start");

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null));
        assertEquals("ReplicationGroupAlreadyExistsFault", ex.jsonType());
        verify(containerManager, never()).stop(any());
        verify(containerManager, never()).stopByGroupId(anyString());

        releaseLatch.countDown();
        firstRequest.join(5000);

        assertEquals("grp", service.getReplicationGroup("grp").getReplicationGroupId());
    }
}

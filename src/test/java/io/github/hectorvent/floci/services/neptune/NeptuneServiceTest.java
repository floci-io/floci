package io.github.hectorvent.floci.services.neptune;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.neptune.container.NeptuneContainerHandle;
import io.github.hectorvent.floci.services.neptune.container.NeptuneContainerManager;
import io.github.hectorvent.floci.services.neptune.model.NeptuneCluster;
import io.github.hectorvent.floci.services.neptune.model.NeptuneDbType;
import io.github.hectorvent.floci.services.neptune.proxy.NeptuneProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class NeptuneServiceTest {

    private NeptuneService service;
    private NeptuneContainerManager containerManager;
    private NeptuneProxyManager proxyManager;

    @BeforeEach
    void setUp() {
        containerManager = mock(NeptuneContainerManager.class);
        proxyManager = mock(NeptuneProxyManager.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        RegionResolver regionResolver = mock(RegionResolver.class);

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.NeptuneServiceConfig neptuneConfig = mock(EmulatorConfig.NeptuneServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.neptune()).thenReturn(neptuneConfig);
        when(neptuneConfig.proxyBasePort()).thenReturn(18182);
        when(neptuneConfig.proxyMaxPort()).thenReturn(18199);
        when(neptuneConfig.dbType()).thenReturn("gremlin");
        when(neptuneConfig.defaultImage()).thenReturn("tinkerpop/gremlin-server:3.7");
        when(neptuneConfig.defaultNeo4jImage()).thenReturn("neo4j:5");
        when(config.hostname()).thenReturn(Optional.of("localhost"));

        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");
        when(regionResolver.buildArn(anyString(), anyString(), anyString()))
                .thenReturn("arn:aws:neptune:us-east-1:000000000000:cluster:c");

        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> new InMemoryStorage<>());
        when(containerManager.start(anyString(), anyString(), any(NeptuneDbType.class)))
                .thenReturn(new NeptuneContainerHandle("cid", "c", "localhost", 8182));
        doNothing().when(proxyManager).startProxy(anyString(), anyInt(), anyString(), anyInt());

        service = new NeptuneService(config, regionResolver, containerManager, proxyManager, storageFactory);
    }

    @Test
    void failedProvisioningRollsBackContainerAndReleasesProxyPort() {
        NeptuneContainerHandle handle = new NeptuneContainerHandle("cid", "c", "localhost", 8182);
        when(containerManager.start(anyString(), anyString(), any(NeptuneDbType.class)))
                .thenReturn(handle);

        // Proxy startup blows up after the port is reserved and the container is started.
        doThrow(new RuntimeException("proxy boom"))
                .when(proxyManager).startProxy(eq("c"), anyInt(), anyString(), anyInt());

        // The original failure must propagate to the caller (we clean up, then rethrow).
        assertThrows(RuntimeException.class,
                () -> service.createDbCluster("c", "1.3.2.1", false));

        // Rollback stopped the proxy and the already-started container (by id).
        verify(proxyManager).stopProxy("c");
        verify(containerManager).stopByClusterId("c");

        // The reserved proxy port was released: a subsequent successful create reuses the base port
        // instead of skipping to the next one (which is what a leak would cause).
        doNothing().when(proxyManager).startProxy(anyString(), anyInt(), anyString(), anyInt());
        NeptuneCluster recovered = service.createDbCluster("c2", "1.3.2.1", false);
        assertEquals(18182, recovered.getProxyPort(),
                "Port from the failed create must be released so the next cluster reuses it");
    }

    @Test
    void jvmErrorDuringProvisioningStillRollsBack() {
        NeptuneContainerHandle handle = new NeptuneContainerHandle("cid", "c", "localhost", 8182);
        when(containerManager.start(anyString(), anyString(), any(NeptuneDbType.class)))
                .thenReturn(handle);

        // A JVM Error (not a RuntimeException) escapes provisioning — a catch (RuntimeException)
        // would miss it, so rollback must run from a finally instead.
        doThrow(new StackOverflowError("boom"))
                .when(proxyManager).startProxy(eq("c"), anyInt(), anyString(), anyInt());

        assertThrows(StackOverflowError.class,
                () -> service.createDbCluster("c", "1.3.2.1", false));

        // Rollback still fired despite the Error: proxy and container stopped, port released.
        verify(proxyManager).stopProxy("c");
        verify(containerManager).stopByClusterId("c");

        doNothing().when(proxyManager).startProxy(anyString(), anyInt(), anyString(), anyInt());
        NeptuneCluster recovered = service.createDbCluster("c2", "1.3.2.1", false);
        assertEquals(18182, recovered.getProxyPort(),
                "Port from the failed create must be released even when the failure is an Error");
    }

    @Test
    void failedContainerStartupCleansUpContainerByIdAndReleasesPort() {
        // containerManager.start(...) throws — this models both a container that never started
        // and (crucially) a readiness timeout, where start() created + registered the container
        // before throwing, so no handle ever reaches the service.
        doThrow(new RuntimeException("readiness boom"))
                .when(containerManager).start(eq("c"), anyString(), any(NeptuneDbType.class));

        // The original failure must propagate to the caller (we clean up, then rethrow).
        assertThrows(RuntimeException.class,
                () -> service.createDbCluster("c", "1.3.2.1", false));

        // No handle returned, so the proxy never started and must not be stopped...
        verify(proxyManager, never()).stopProxy(anyString());
        // ...but the container must still be cleaned up by id, since start() may have created and
        // registered it before failing. Cleaning up by handle here would orphan it.
        verify(containerManager).stopByClusterId("c");

        // The reserved proxy port was still released: a subsequent successful create reuses the base port.
        when(containerManager.start(anyString(), anyString(), any(NeptuneDbType.class)))
                .thenReturn(new NeptuneContainerHandle("cid", "c2", "localhost", 8182));
        NeptuneCluster recovered = service.createDbCluster("c2", "1.3.2.1", false);
        assertEquals(18182, recovered.getProxyPort(),
                "Port from the failed create must be released so the next cluster reuses it");
    }
}

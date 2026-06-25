package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerHandle;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerManager;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.ClusterStatus;
import io.github.hectorvent.floci.services.memorydb.proxy.MemoryDbProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryDbServiceTest {

    private MemoryDbService service;
    private MemoryDbContainerManager containerManager;
    private EmulatorConfig.MemoryDbServiceConfig mdbConfig;

    @BeforeEach
    void setUp() {
        containerManager = mock(MemoryDbContainerManager.class);
        MemoryDbProxyManager proxyManager = mock(MemoryDbProxyManager.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        RegionResolver regionResolver = mock(RegionResolver.class);

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        mdbConfig = mock(EmulatorConfig.MemoryDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.memorydb()).thenReturn(mdbConfig);
        when(mdbConfig.proxyBasePort()).thenReturn(16400);
        when(mdbConfig.proxyMaxPort()).thenReturn(16419);
        when(mdbConfig.defaultImage()).thenReturn("valkey/valkey:8");
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        when(regionResolver.getAccountId()).thenReturn("000000000000");

        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> new InMemoryStorage<>());
        when(containerManager.start(anyString(), anyString()))
                .thenReturn(new MemoryDbContainerHandle("cid", "cluster", "localhost", 6379));
        doNothing().when(proxyManager).startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());

        service = new MemoryDbService(containerManager, proxyManager, storageFactory, config, regionResolver);
    }

    @Test
    void createAndDescribeCluster() {
        Cluster spec = new Cluster();
        spec.setName("my-cluster");
        spec.setNodeType("db.t4g.small");
        Cluster created = service.createCluster(spec, "us-east-1");

        assertEquals("my-cluster", created.getName());
        assertEquals(ClusterStatus.AVAILABLE, created.getStatus());
        assertEquals("arn:aws:memorydb:us-east-1:000000000000:cluster/my-cluster", created.getArn());
        assertEquals("localhost", created.getClusterEndpoint().address());

        assertEquals(1, service.describeClusters("my-cluster").size());
        assertEquals(1, service.describeClusters(null).size());
    }

    @Test
    void duplicateClusterRejected() {
        Cluster spec = new Cluster();
        spec.setName("dupe");
        service.createCluster(spec, "us-east-1");

        Cluster again = new Cluster();
        again.setName("dupe");
        assertThrows(AwsException.class, () -> service.createCluster(again, "us-east-1"));
    }

    @Test
    void deleteClusterRemovesIt() {
        Cluster spec = new Cluster();
        spec.setName("temp");
        service.createCluster(spec, "us-east-1");

        service.deleteCluster("temp");

        assertThrows(AwsException.class, () -> service.getCluster("temp"));
    }

    @Test
    void tagAndUntagResource() {
        Cluster spec = new Cluster();
        spec.setName("tagged");
        Cluster created = service.createCluster(spec, "us-east-1");
        String arn = created.getArn();

        service.tagResource(arn, Map.of("env", "dev"));
        assertEquals("dev", service.listTags(arn).get("env"));

        service.untagResource(arn, List.of("env"));
        assertFalse(service.listTags(arn).containsKey("env"));
    }

    @Test
    void passwordValidationUsesAuthToken() {
        Cluster spec = new Cluster();
        spec.setName("secure");
        spec.setAuthMode(AuthMode.PASSWORD);
        spec.setAuthToken("s3cret");
        service.createCluster(spec, "us-east-1");

        assertTrue(service.validatePassword("secure", null, "s3cret"));
        assertFalse(service.validatePassword("secure", null, "wrong"));
    }

    @Test
    void nullClusterNameYieldsValidationErrorNotNpe() {
        AwsException ex = assertThrows(AwsException.class, () -> service.getCluster(null));
        assertEquals(400, ex.getHttpStatus());
        assertThrows(AwsException.class, () -> service.deleteCluster(null));
        assertThrows(AwsException.class, () -> service.updateCluster("  ", "desc"));
    }

    @Test
    void failedProvisioningReleasesProxyPort() {
        when(mdbConfig.proxyBasePort()).thenReturn(16400);
        when(mdbConfig.proxyMaxPort()).thenReturn(16400); // exactly one port available
        when(containerManager.start(anyString(), anyString()))
                .thenThrow(new RuntimeException("docker unavailable"))
                .thenReturn(new MemoryDbContainerHandle("cid", "c2", "localhost", 6379));

        Cluster first = new Cluster();
        first.setName("c1");
        assertThrows(RuntimeException.class, () -> service.createCluster(first, "us-east-1"));

        // The single port must have been released, so a second create still succeeds
        Cluster second = new Cluster();
        second.setName("c2");
        Cluster created = service.createCluster(second, "us-east-1");
        assertEquals(ClusterStatus.AVAILABLE, created.getStatus());
    }

    @Test
    void mockModeSkipsContainerAndReportsStandardPort() {
        when(mdbConfig.mock()).thenReturn(true);

        Cluster spec = new Cluster();
        spec.setName("mock-cluster");
        Cluster created = service.createCluster(spec, "us-east-1");

        assertEquals(ClusterStatus.AVAILABLE, created.getStatus());
        assertEquals("localhost", created.getClusterEndpoint().address());
        assertEquals(6379, created.getClusterEndpoint().port());
        verifyNoInteractions(containerManager);
    }
}

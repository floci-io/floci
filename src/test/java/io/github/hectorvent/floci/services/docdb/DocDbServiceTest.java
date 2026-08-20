package io.github.hectorvent.floci.services.docdb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.docdb.container.DocDbContainerManager;
import io.github.hectorvent.floci.services.docdb.model.DocDbCluster;
import io.github.hectorvent.floci.services.docdb.model.DocDbInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocDbServiceTest {

    private DocDbService docDbService;
    private DocDbContainerManager containerManager;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var docdbConfig = Mockito.mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.docdb()).thenReturn(docdbConfig);
        when(docdbConfig.mock()).thenReturn(true);

        containerManager = Mockito.mock(DocDbContainerManager.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        docDbService = new DocDbService(config, regionResolver, containerManager, storageFactory);
    }

    @Test
    void createClusterInMockModeSkipsContainer() {
        DocDbCluster cluster = docDbService.createDbCluster(
                "mock-cluster", null, "admin", "secret", false);

        assertNotNull(cluster);
        assertEquals("mock-cluster", cluster.getDbClusterIdentifier());
        assertEquals("available", cluster.getStatus());
        assertEquals("localhost", cluster.getEndpoint());
        assertEquals(27017, cluster.getPort());
        assertTrue(cluster.getDbClusterArn().contains("mock-cluster"));

        verify(containerManager, never()).start(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void describeClusterInMockMode() {
        docDbService.createDbCluster("mock-cluster", null, "admin", "secret", false);

        DocDbCluster described = docDbService.getDbCluster("mock-cluster");
        assertEquals("mock-cluster", described.getDbClusterIdentifier());
        assertEquals("available", described.getStatus());
    }

    @Test
    void createInstanceInMockMode() {
        docDbService.createDbCluster("mock-cluster", null, "admin", "secret", false);

        DocDbInstance instance = docDbService.createDbInstance(
                "mock-instance", "mock-cluster", "db.r5.large", null, false);

        assertNotNull(instance);
        assertEquals("mock-instance", instance.getDbInstanceIdentifier());
        assertEquals("mock-cluster", instance.getDbClusterIdentifier());
        assertEquals("available", instance.getStatus());
        assertEquals("localhost", instance.getEndpoint());
        assertEquals(27017, instance.getPort());
    }

    @Test
    void listDbClustersMatchesByArnButNotForeignArn() {
        DocDbCluster cluster = docDbService.createDbCluster(
                "mock-cluster", null, "admin", "secret", false);

        String arn = cluster.getDbClusterArn();
        assertEquals(1, docDbService.listDbClusters(arn).size());
        assertTrue(docDbService.listDbClusters(arn.replace("000000000000", "999999999999")).isEmpty(),
                "cross-account ARN must not match");
        assertTrue(docDbService.listDbClusters(arn.replace("us-east-1", "eu-west-1")).isEmpty(),
                "cross-region ARN must not match");
    }

    @Test
    void listDbInstancesMatchesByArnButNotForeignArn() {
        docDbService.createDbCluster("mock-cluster", null, "admin", "secret", false);
        DocDbInstance instance = docDbService.createDbInstance(
                "mock-instance", "mock-cluster", "db.r5.large", null, false);

        String arn = instance.getDbInstanceArn();
        assertEquals(1, docDbService.listDbInstances(arn).size());
        assertTrue(docDbService.listDbInstances(arn.replace("000000000000", "999999999999")).isEmpty(),
                "cross-account ARN must not match");
        assertTrue(docDbService.listDbInstances(arn.replace("us-east-1", "eu-west-1")).isEmpty(),
                "cross-region ARN must not match");
    }

    @Test
    void deleteClusterInMockModeSkipsContainerStop() {
        docDbService.createDbCluster("mock-cluster", null, "admin", "secret", false);

        docDbService.deleteDbCluster("mock-cluster");

        assertTrue(docDbService.listDbClusters(null).isEmpty());
        verify(containerManager, never()).stop(any());
    }

    @Test
    void createWithoutDockerDaemonStillReachesAvailable() {
        // tryStart() returns null when no Docker daemon is reachable. The cluster record is
        // metadata, so the create still succeeds and the cluster reaches 'available' on the
        // first describe (what SDK/Terraform waiters poll).
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var docdbConfig = Mockito.mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.docdb()).thenReturn(docdbConfig);
        when(docdbConfig.mock()).thenReturn(false);
        when(docdbConfig.defaultImage()).thenReturn("mongo:7.0");
        when(config.hostname()).thenReturn(java.util.Optional.of("localhost"));

        DocDbContainerManager noDaemonContainerManager = Mockito.mock(DocDbContainerManager.class);
        when(noDaemonContainerManager.tryStart(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        DocDbService noDaemonService = new DocDbService(config, regionResolver, noDaemonContainerManager, storageFactory);

        DocDbCluster created = noDaemonService.createDbCluster(
                "no-docker-cluster", null, "admin", "secret", false);

        assertEquals("available", created.getStatus());
        assertEquals("localhost", created.getEndpoint());
        assertEquals(27017, created.getPort());

        assertEquals("no-docker-cluster",
                noDaemonService.getDbCluster("no-docker-cluster").getDbClusterIdentifier());

        // Delete must not reach for a container that was never created.
        noDaemonService.deleteDbCluster("no-docker-cluster");
        verify(noDaemonContainerManager, never()).stop(any());
    }

    @Test
    void tagsAreAnsweredOnlyForAnArnInThisRegionAndAccount() {
        // The router reaches this service only for an ARN it already matched, so these checks
        // guard the service's own contract: storage is keyed by identifier alone, and answering
        // by name would hand this caller's cluster to an ARN naming somewhere else. The message
        // is the one a live account gives, which is the same for both cases.
        docDbService.createDbCluster("scoped-cluster", null, "admin", "secret", false);
        String arn = docDbService.getDbCluster("scoped-cluster").getDbClusterArn();
        docDbService.addTagsToResource(arn, java.util.Map.of("env", "test"));
        assertEquals(java.util.Map.of("env", "test"), docDbService.listTagsForResource(arn));

        for (String foreign : new String[]{
                "arn:aws:rds:eu-west-1:000000000000:cluster:scoped-cluster",
                "arn:aws:rds:us-east-1:111122223333:cluster:scoped-cluster"}) {
            AwsException rejected = assertThrows(AwsException.class,
                    () -> docDbService.listTagsForResource(foreign));
            assertEquals("InvalidParameterValue", rejected.getErrorCode());
            assertTrue(rejected.getMessage().contains("does not match an RDS resource in this region"));
        }

        AwsException notAnArn = assertThrows(AwsException.class,
                () -> docDbService.listTagsForResource("scoped-cluster"));
        assertTrue(notAnArn.getMessage().contains("Invalid resource name"));
    }
}

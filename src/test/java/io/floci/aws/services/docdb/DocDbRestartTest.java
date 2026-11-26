package io.floci.aws.services.docdb;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.docdb.container.DocDbContainerHandle;
import io.floci.aws.services.docdb.container.DocDbContainerManager;
import io.floci.aws.services.docdb.model.DocDbCluster;
import io.floci.aws.services.docdb.model.DocDbInstance;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.rds.RdsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** #5664: a DocumentDB cluster's container comes back when Floci restarts, on its volume and with its password. */
class DocDbRestartTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";

    private final AtomicInteger started = new AtomicInteger();
    /** The Region requests are signed for, which a test switches to create a cluster elsewhere. */
    private volatile String requestRegion = "us-east-1";
    /** Fails every write of an available instance, once set. */
    private final AtomicBoolean refuseAvailableInstances = new AtomicBoolean();
    /** Runs once, inside the next read of instance {@code c-1}. */
    private volatile Runnable onInstanceRead;
    /** The cluster whose marking for the restore the cluster store refuses to write. */
    private volatile String refuseMarking;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private RegionResolver regionResolver;
    private EmulatorConfig.DocDbServiceConfig docdbConfig;
    private DocDbContainerManager containers;
    private StorageBackend<String, DocDbCluster> clusterStore;
    private DocDbService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> {
            StorageBackend<String, Object> store = AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT);
            if ("docdb-clusters.json".equals(inv.getArgument(1))) {
                AccountAwareStorageBackend<DocDbCluster> clustersStore =
                        new AccountAwareStorageBackend<>(new ClusterStore(), null, DEFAULT_ACCOUNT);
                clusterStore = clustersStore;
                return clustersStore;
            }
            if ("docdb-instances.json".equals(inv.getArgument(1))) {
                return new AccountAwareStorageBackend<>(new InstanceStore(), null, DEFAULT_ACCOUNT);
            }
            return store;
        });
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        docdbConfig = mock(EmulatorConfig.DocDbServiceConfig.class);
        EmulatorConfig.StorageConfig storageConfig = mock(EmulatorConfig.StorageConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.docdb()).thenReturn(docdbConfig);
        when(docdbConfig.mock()).thenReturn(false);
        when(docdbConfig.defaultImage()).thenReturn("mongo:7.0");
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.mode()).thenReturn("persistent");

        regionResolver = spy(new RegionResolver("us-east-1", DEFAULT_ACCOUNT));
        doAnswer(inv -> requestRegion).when(regionResolver).getRegion();
        containers = mock(DocDbContainerManager.class);
        startsContainers();
        when(containers.isShuttingDown()).thenAnswer(inv -> shuttingDown.get());
        RdsService rdsService = mock(RdsService.class);
        when(rdsService.isManagedClusterParameterGroup(any())).thenReturn(true);
        Ec2Service ec2Service = mock(Ec2Service.class);
        when(ec2Service.resolveDefaultSecurityGroupId(anyString()))
                .thenAnswer(inv -> Ec2Service.defaultSecurityGroupId(inv.getArgument(0), DEFAULT_ACCOUNT));
        service = new DocDbService(config, regionResolver, containers, storageFactory, rdsService, ec2Service,
                mock(KmsService.class));
    }

    @Test
    void eachClustersContainerStartsAgainOnItsVolumeWithItsPassword() {
        DocDbCluster orders = service.createDbCluster("orders", null, "admin", "secret-a", false);
        service.createDbInstance("orders-1", "orders", null, null, false);
        String before = orders.getContainerId();
        int portBefore = orders.getPort();
        requestRegion = "eu-west-1";
        DocDbCluster other = service.createDbCluster("orders", null, "owner", "secret-b", false);
        requestRegion = "us-east-1";
        String otherBefore = other.getContainerId();
        assertEquals("secret-a", orders.getMasterPassword());
        clearInvocations(containers);

        service.restorePersistedRuntime().join();

        verify(containers).tryStart("orders", "mongo:7.0", "admin", "secret-a", "us-east-1", orders.getVolumeId());
        verify(containers).tryStart("orders", "mongo:7.0", "owner", "secret-b", "eu-west-1", other.getVolumeId());
        DocDbCluster restored = service.getDbCluster("orders");
        assertEquals("available", restored.getStatus());
        assertNotEquals(before, restored.getContainerId());
        assertNotEquals(portBefore, restored.getPort());
        DocDbInstance instance = service.getDbInstance("orders-1");
        assertEquals("available", instance.getStatus());
        assertEquals(restored.getPort(), instance.getPort());
        DocDbCluster restoredOther = service.getDbCluster("orders", "eu-west-1");
        assertEquals("available", restoredOther.getStatus());
        assertNotEquals(otherBefore, restoredOther.getContainerId());
        assertNotEquals(restored.getContainerId(), restoredOther.getContainerId());
    }

    @Test
    void aClusterIsInMaintenanceAndItsInstancesStartingWhileItsContainerComesBack() throws Exception {
        service.createDbCluster("c", null, "admin", "secret", false);
        service.createDbInstance("c-1", "c", null, null, false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockStarts(starting, release);

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertEquals("maintenance", service.getDbCluster("c").getStatus());
        assertEquals("starting", service.getDbInstance("c-1").getStatus());
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        assertEquals("maintenance", service.getDbCluster("c").getStatus());
        release.countDown();
        restore.join();

        assertEquals("available", service.getDbCluster("c").getStatus());
        assertEquals("available", service.getDbInstance("c-1").getStatus());
    }

    @Test
    void withNoDockerDaemonAClusterComesBackWithoutAContainer() {
        service.createDbCluster("c", null, "admin", "secret", false);
        service.createDbInstance("c-1", "c", null, null, false);
        doReturn(null).when(containers).tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any());

        service.restorePersistedRuntime().join();

        DocDbCluster cluster = service.getDbCluster("c");
        assertEquals("available", cluster.getStatus());
        assertNull(cluster.getContainerId());
        assertEquals("localhost", cluster.getEndpoint());
        assertEquals(27017, cluster.getPort());
        assertEquals("available", service.getDbInstance("c-1").getStatus());
        assertEquals(27017, service.getDbInstance("c-1").getPort());
    }

    @Test
    void aClusterWhoseContainerDoesNotStartIsLeftWithoutOneAndTriedAgainAtTheNextStart() {
        service.createDbCluster("bad", null, "admin", "secret", false);
        service.createDbInstance("bad-1", "bad", null, null, false);
        service.createDbCluster("good", null, "admin", "secret", false);
        doThrow(new IllegalStateException("no such image: mongo:7.0"))
                .when(containers).tryStart(eq("bad"), anyString(), anyString(), anyString(), anyString(), any());

        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.restorePersistedRuntime().join());

        assertTrue(failure.getCause().getMessage().contains("bad"), failure.getCause().getMessage());
        assertEquals("available", service.getDbCluster("good").getStatus());
        DocDbCluster bad = service.getDbCluster("bad");
        assertEquals("available", bad.getStatus());
        assertNull(bad.getContainerId());
        assertEquals("failed", service.getDbInstance("bad-1").getStatus());

        startsContainers();
        service.restorePersistedRuntime().join();
        assertNotNull(service.getDbCluster("bad").getContainerId());
        assertEquals("available", service.getDbInstance("bad-1").getStatus());
    }

    @Test
    void aClusterRecordedBeforeThePasswordWasKeptReadsItFromTheContainerItLeft() {
        DocDbCluster cluster = service.createDbCluster("c", null, "admin", "secret", false);
        String leftBehind = cluster.getContainerId();
        cluster.setMasterPassword(null);
        when(containers.survivingMasterPassword(leftBehind)).thenReturn("from-its-env");

        service.restorePersistedRuntime().join();

        verify(containers).tryStart("c", "mongo:7.0", "admin", "from-its-env", "us-east-1", cluster.getVolumeId());
        assertEquals("from-its-env", service.getDbCluster("c").getMasterPassword());
    }

    @Test
    void aPasswordReadFromTheContainerLeftBehindIsKeptEvenWhenTheStartFails() {
        DocDbCluster cluster = service.createDbCluster("c", null, "admin", "secret", false);
        when(containers.survivingMasterPassword(cluster.getContainerId())).thenReturn("from-its-env");
        cluster.setMasterPassword(null);
        doThrow(new IllegalStateException("no such image: mongo:7.0"))
                .when(containers).tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any());

        assertThrows(CompletionException.class, () -> service.restorePersistedRuntime().join());

        assertEquals("from-its-env", service.getDbCluster("c").getMasterPassword());
        startsContainers();
        clearInvocations(containers);
        service.restorePersistedRuntime().join();
        verify(containers).tryStart("c", "mongo:7.0", "admin", "from-its-env", "us-east-1", cluster.getVolumeId());
    }

    @Test
    void aClusterWithNoPasswordToStartWithIsLeftWithoutAContainer() {
        DocDbCluster cluster = service.createDbCluster("c", null, "admin", "secret", false);
        service.createDbInstance("c-1", "c", null, null, false);
        cluster.setMasterPassword(null);
        clearInvocations(containers);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.restorePersistedRuntime().join());

        assertTrue(failure.getCause().getCause().getMessage().contains("before Floci kept master passwords"),
                failure.getCause().getCause().getMessage());
        verify(containers, never()).tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        assertNull(service.getDbCluster("c").getContainerId());
        assertEquals("failed", service.getDbInstance("c-1").getStatus());
    }

    @Test
    void aClusterRecordedBeforeItsDataWasKeptGetsAVolumeOfItsOwn() {
        DocDbCluster cluster = service.createDbCluster("c", null, "admin", "secret", false);
        cluster.setVolumeId(null);
        clearInvocations(containers);

        service.restorePersistedRuntime().join();

        ArgumentCaptor<String> volume = ArgumentCaptor.forClass(String.class);
        verify(containers).tryStart(eq("c"), anyString(), anyString(), anyString(), anyString(), volume.capture());
        assertNotNull(volume.getValue());
        assertEquals(volume.getValue(), service.getDbCluster("c").getVolumeId());
    }

    @Test
    void aDeleteFlociDidNotLiveToFinishIsFinishedAtStartup() {
        DocDbCluster cluster = service.createDbCluster("c", null, "admin", "secret", false);
        cluster.setStatus("deleting");
        clearInvocations(containers);

        service.restorePersistedRuntime().join();

        assertFalse(service.hasCluster("c"));
        verify(containers).stop(argThat(handle -> cluster.getContainerId().equals(handle.getContainerId())));
        verify(containers).removeStorage(cluster.getVolumeId());
        verify(containers, never()).tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aClusterDeletedDuringItsRestoreGoesOnceItsContainerHasStarted() throws Exception {
        DocDbCluster cluster = service.createDbCluster("c", null, "admin", "secret", false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockStarts(starting, release);

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        service.deleteDbCluster("c");
        assertEquals("deleting", service.getDbCluster("c").getStatus());
        verify(containers, never()).removeStorage(any());
        release.countDown();
        restore.join();

        assertFalse(service.hasCluster("c"));
        verify(containers).stop(argThat(handle -> "container-2".equals(handle.getContainerId())));
        verify(containers).removeStorage(cluster.getVolumeId());
    }

    @Test
    void aSecondDeleteDuringTheRestoreStillWaitsForTheContainer() throws Exception {
        service.createDbCluster("c", null, "admin", "secret", false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockStarts(starting, release);

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        service.deleteDbCluster("c");
        service.deleteDbCluster("c");
        assertEquals("deleting", service.getDbCluster("c").getStatus());
        release.countDown();
        restore.join();

        assertFalse(service.hasCluster("c"));
        verify(containers).stop(argThat(handle -> "container-2".equals(handle.getContainerId())));
    }

    @Test
    void noInstanceJoinsAClusterWhileItsContainerComesBackOrItWaitsToBeDeleted() throws Exception {
        service.createDbCluster("c", null, "admin", "secret", false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockStarts(starting, release);

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        AwsException whileStarting = assertThrows(AwsException.class,
                () -> service.createDbInstance("c-1", "c", null, null, false));
        service.deleteDbCluster("c");
        AwsException whileDeleting = assertThrows(AwsException.class,
                () -> service.createDbInstance("c-1", "c", null, null, false));
        release.countDown();
        restore.join();

        for (AwsException refused : List.of(whileStarting, whileDeleting)) {
            assertEquals("InvalidDBClusterStateFault", refused.getErrorCode());
            assertEquals(400, refused.getHttpStatus());
        }
        assertFalse(service.hasCluster("c"));
        assertFalse(service.hasInstance("c-1"));
    }

    @Test
    void aClusterDeletedWhileItsStartFailsIsStillRemoved() throws Exception {
        service.createDbCluster("c", null, "admin", "secret", false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            starting.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            throw new IllegalStateException("no such image: mongo:7.0");
        }).when(containers).tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any());

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        service.deleteDbCluster("c");
        release.countDown();

        assertThrows(CompletionException.class, restore::join);
        assertFalse(service.hasCluster("c"));
    }

    @Test
    void aClusterGoneDuringItsRestoreHasTheContainerStartedForItStopped() throws Exception {
        service.createDbCluster("c", null, "admin", "secret", false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockStarts(starting, release);

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        clusterStore.delete("us-east-1::c");
        release.countDown();
        restore.join();

        assertFalse(service.hasCluster("c"));
        verify(containers).stop(argThat(handle -> "container-2".equals(handle.getContainerId())));
    }

    @Test
    void aClusterReplacedDuringItsRestoreKeepsItsOwnRecord() throws Exception {
        service.createDbCluster("c", null, "admin", "secret", false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockStarts(starting, release);

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        DocDbCluster replacement = new DocDbCluster();
        replacement.setDbClusterIdentifier("c");
        replacement.setDbClusterResourceId("cluster-REPLACEMENT");
        replacement.setStatus("available");
        replacement.setContainerId("replacement-container");
        replacement.setMasterPassword("its-own");
        clusterStore.put("us-east-1::c", replacement);
        release.countDown();
        restore.join();

        DocDbCluster stored = service.getDbCluster("c");
        assertEquals("replacement-container", stored.getContainerId());
        assertEquals("its-own", stored.getMasterPassword());
        verify(containers).stop(argThat(handle -> "container-2".equals(handle.getContainerId())));
    }

    @Test
    void aVolumeGivenAtTheRestoreIsKeptEvenWhenTheStartFails() {
        DocDbCluster cluster = service.createDbCluster("c", null, "admin", "secret", false);
        cluster.setVolumeId(null);
        doThrow(new IllegalStateException("no such image: mongo:7.0"))
                .when(containers).tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        clearInvocations(containers);

        assertThrows(CompletionException.class, () -> service.restorePersistedRuntime().join());

        String given = service.getDbCluster("c").getVolumeId();
        assertNotNull(given);
        verify(containers).tryStart(eq("c"), anyString(), anyString(), anyString(), anyString(), eq(given));
        startsContainers();
        service.restorePersistedRuntime().join();
        assertEquals(given, service.getDbCluster("c").getVolumeId());
    }

    @Test
    void aClusterThatCannotBeMarkedIsReportedWithoutStoppingStartupOrTheOthers() {
        service.createDbCluster("broken", null, "admin", "secret", false);
        service.createDbCluster("good", null, "admin", "secret", false);
        refuseMarking = "broken";
        clearInvocations(containers);

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        CompletionException failure = assertThrows(CompletionException.class, restore::join);

        assertTrue(failure.getCause().getMessage().contains("broken"), failure.getCause().getMessage());
        assertEquals("available", service.getDbCluster("good").getStatus());
        verify(containers).tryStart(eq("good"), anyString(), anyString(), anyString(), anyString(), any());
        verify(containers, never()).tryStart(eq("broken"), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void finishingALeftoverDeleteDoesNotHoldUpStartup() {
        service.createDbCluster("gone", null, "admin", "secret", false).setStatus("deleting");
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return null;
        }).when(containers).stop(any());

        CompletableFuture<Void> restore = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> service.restorePersistedRuntime());
        release.countDown();
        restore.join();

        assertFalse(service.hasCluster("gone"));
    }

    @Test
    void aShutdownDuringTheRestoreLeavesTheClustersForTheNextStart() {
        service.createDbCluster("first", null, "admin", "secret", false);
        service.createDbInstance("first-1", "first", null, null, false);
        service.createDbCluster("second", null, "admin", "secret", false);
        service.createDbInstance("second-1", "second", null, null, false);
        clearInvocations(containers);
        doAnswer(inv -> {
            shuttingDown.set(true);
            throw new IllegalStateException("DocumentDB is shutting down");
        }).when(containers).tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any());

        service.restorePersistedRuntime().join();

        verify(containers, times(1)).tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        for (String id : List.of("first", "second")) {
            assertEquals("maintenance", service.getDbCluster(id).getStatus());
            assertEquals("starting", service.getDbInstance(id + "-1").getStatus());
        }
    }

    @Test
    void aRestoreThatFailsOnceItsContainerHasStartedStopsThatContainer() {
        service.createDbCluster("c", null, "admin", "secret", false);
        service.createDbInstance("c-1", "c", null, null, false);
        refuseAvailableInstances.set(true);

        assertThrows(CompletionException.class, () -> service.restorePersistedRuntime().join());

        verify(containers).stop(argThat(handle -> "container-2".equals(handle.getContainerId())));
        assertNull(service.getDbCluster("c").getContainerId());
        assertEquals("failed", service.getDbInstance("c-1").getStatus());
    }

    @Test
    void anInstanceMovedToAnotherClusterDuringTheRestoreKeepsThatClustersEndpoint() throws Exception {
        service.createDbCluster("c", null, "admin", "secret", false);
        service.createDbInstance("c-1", "c", null, null, false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockStarts(starting, release);
        doReturn(new DocDbContainerHandle("container-d", "d", "localhost", 49999))
                .when(containers).tryStart(eq("d"), anyString(), anyString(), anyString(), anyString(), any());

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        service.createDbCluster("d", null, "admin", "secret", false);
        onInstanceRead = () -> {
            service.deleteDbInstance("c-1");
            service.createDbInstance("c-1", "d", null, null, false);
        };
        release.countDown();
        restore.join();

        DocDbInstance moved = service.getDbInstance("c-1");
        assertEquals("d", moved.getDbClusterIdentifier());
        assertEquals(49999, moved.getPort());
        assertEquals("available", moved.getStatus());
    }

    @Test
    void aDeleteAfterAFailedRestoreRemovesTheClusterAtOnce() {
        service.createDbCluster("c", null, "admin", "secret", false);
        doThrow(new IllegalStateException("no such image: mongo:7.0"))
                .when(containers).tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        assertThrows(CompletionException.class, () -> service.restorePersistedRuntime().join());

        service.deleteDbCluster("c");

        assertFalse(service.hasCluster("c"));
    }

    @Test
    void aDeleteAfterTheRestoreRemovesTheClusterAtOnce() {
        service.createDbCluster("c", null, "admin", "secret", false);
        service.restorePersistedRuntime().join();

        service.deleteDbCluster("c");

        assertFalse(service.hasCluster("c"));
    }

    @Test
    void mockClustersAreLeftAsTheyAre() {
        when(docdbConfig.mock()).thenReturn(true);
        DocDbCluster cluster = service.createDbCluster("c", null, "admin", "secret", false);
        clearInvocations(containers);

        service.restorePersistedRuntime().join();

        assertNull(cluster.getMasterPassword());
        assertEquals("available", service.getDbCluster("c").getStatus());
        verify(containers, never()).tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aDeleteThatCannotFinishAtStartupIsReportedWithoutStoppingTheOthers() {
        DocDbCluster gone = service.createDbCluster("gone", null, "admin", "secret", false);
        gone.setStatus("deleting");
        service.createDbCluster("good", null, "admin", "secret", false);
        doThrow(new IllegalStateException("Docker refused")).when(containers).stop(any());
        clearInvocations(containers);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.restorePersistedRuntime().join());

        assertTrue(failure.getCause().getMessage().contains("gone"), failure.getCause().getMessage());
        assertEquals("available", service.getDbCluster("good").getStatus());
        verify(containers).tryStart(eq("good"), anyString(), anyString(), anyString(), anyString(), any());
        assertEquals("deleting", service.getDbCluster("gone").getStatus());
    }

    /** Each start gives a container of its own, numbered in start order, its port after it. */
    private void startsContainers() {
        doAnswer(inv -> {
            int n = started.incrementAndGet();
            return new DocDbContainerHandle("container-" + n, inv.getArgument(0), "localhost", 40000 + n);
        }).when(containers).tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    /** Starts that signal {@code starting} and wait for {@code release} before giving their container. */
    private void blockStarts(CountDownLatch starting, CountDownLatch release) {
        doAnswer(inv -> {
            starting.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            int n = started.incrementAndGet();
            return new DocDbContainerHandle("container-" + n, inv.getArgument(0), "localhost", 40000 + n);
        }).when(containers).tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    /** The cluster store, which a test can have refuse to mark a cluster for the restore. */
    private final class ClusterStore extends InMemoryStorage<String, DocDbCluster> {
        @Override
        public synchronized void put(String key, DocDbCluster value) {
            if ("maintenance".equals(value.getStatus()) && value.getDbClusterIdentifier().equals(refuseMarking)) {
                throw new IllegalStateException("the cluster store refused the write");
            }
            super.put(key, value);
        }
    }

    /** The instance store, which a test can refuse writes to or act inside a read of. */
    private final class InstanceStore extends InMemoryStorage<String, DocDbInstance> {
        @Override
        public synchronized void put(String key, DocDbInstance value) {
            if (refuseAvailableInstances.get() && "available".equals(value.getStatus())) {
                throw new IllegalStateException("the instance store refused the write");
            }
            super.put(key, value);
        }

        @Override
        public Optional<DocDbInstance> get(String key) {
            Runnable hook = onInstanceRead;
            if (hook != null && key.endsWith("::c-1")) {
                onInstanceRead = null;
                hook.run();
            }
            return super.get(key);
        }
    }
}

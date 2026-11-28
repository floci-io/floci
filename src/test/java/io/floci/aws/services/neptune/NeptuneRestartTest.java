package io.floci.aws.services.neptune;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.neptune.container.NeptuneContainerHandle;
import io.floci.aws.services.neptune.container.NeptuneContainerManager;
import io.floci.aws.services.neptune.model.NeptuneCluster;
import io.floci.aws.services.neptune.model.NeptuneDbType;
import io.floci.aws.services.neptune.model.NeptuneInstance;
import io.floci.aws.services.neptune.proxy.NeptuneProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** #5664: a Neptune cluster's container and proxy come back when Floci restarts, on its volume and its port. */
class NeptuneRestartTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String GREMLIN_IMAGE = "tinkerpop/gremlin-server:3.7";
    private static final String NEO4J_IMAGE = "neo4j:5";

    private final AtomicInteger started = new AtomicInteger();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    /** The stores a restarted service reads back, by file. */
    private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
    /** The cluster whose marking for the restore the cluster store refuses to write. */
    private volatile String refuseMarking;
    /** A cluster and status, {@code id:status}, the cluster store refuses to write once. */
    private volatile String refuse;
    /** Runs once, inside the next read of instance {@code c-1}. */
    private volatile Runnable onInstanceRead;
    /** An instance and status, {@code id:status}, the instance store refuses to write once. */
    private volatile String refuseInstance;
    private volatile String dbType = "gremlin";
    private EmulatorConfig config;
    private RegionResolver regionResolver;
    private StorageFactory storageFactory;
    private NeptuneContainerManager containers;
    private NeptuneProxyManager proxies;
    private NeptuneService service;

    @BeforeEach
    void setUp() {
        regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT);
        when(regionResolver.getRegion()).thenReturn(REGION);
        when(regionResolver.getDefaultRegion()).thenReturn(REGION);
        when(regionResolver.getDefaultAccountId()).thenReturn(ACCOUNT);
        when(regionResolver.buildArn(anyString(), anyString(), anyString())).thenAnswer(inv ->
                "arn:aws:" + inv.getArgument(0) + ":" + inv.getArgument(1) + ":" + ACCOUNT + ":"
                        + inv.getArgument(2));

        config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.NeptuneServiceConfig neptuneConfig = mock(EmulatorConfig.NeptuneServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.neptune()).thenReturn(neptuneConfig);
        when(neptuneConfig.proxyBasePort()).thenReturn(18182);
        when(neptuneConfig.proxyMaxPort()).thenReturn(18199);
        when(neptuneConfig.dbType()).thenAnswer(inv -> dbType);
        when(neptuneConfig.defaultImage()).thenReturn(GREMLIN_IMAGE);
        when(neptuneConfig.defaultNeo4jImage()).thenReturn(NEO4J_IMAGE);
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        EmulatorConfig.ServiceStorageOverrides overrides = mock(EmulatorConfig.ServiceStorageOverrides.class);
        EmulatorConfig.NeptuneStorageConfig neptuneStorage = mock(EmulatorConfig.NeptuneStorageConfig.class);
        when(config.storage()).thenReturn(storage);
        when(storage.mode()).thenReturn("persistent");
        when(storage.services()).thenReturn(overrides);
        when(overrides.neptune()).thenReturn(neptuneStorage);
        when(neptuneStorage.mode()).thenReturn(Optional.empty());

        storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv ->
                stores.computeIfAbsent(inv.getArgument(1), file -> switch (file) {
                    case "neptune-clusters.json" -> new AccountAwareStorageBackend<>(new ClusterStore(), null, ACCOUNT);
                    case "neptune-instances.json" -> new AccountAwareStorageBackend<>(new InstanceStore(), null,
                            ACCOUNT);
                    default -> AccountAwareStorageBackend.inMemory(ACCOUNT);
                }));

        containers = mock(NeptuneContainerManager.class);
        proxies = mock(NeptuneProxyManager.class);
        startsContainers();
        when(containers.isShuttingDown()).thenAnswer(inv -> shuttingDown.get());
        service = new NeptuneService(config, regionResolver, containers, proxies, storageFactory);
    }

    @Test
    void aClustersContainerAndProxyComeBackOnItsVolumeAndPort() {
        NeptuneCluster cluster = service.createDbCluster("c", null, false);
        service.createDbInstance("c-1", "c", null, null, false);
        clearInvocations(containers, proxies);

        restarted().restorePersistedRuntime().join();

        verify(containers).tryStart("c", GREMLIN_IMAGE, NeptuneDbType.GREMLIN, ACCOUNT, REGION, cluster.getVolumeId());
        verify(proxies).startProxy(identity("c"), cluster.getProxyPort(), "localhost", 40002);
        NeptuneCluster restored = service.getDbCluster("c");
        assertEquals("available", restored.getStatus());
        assertEquals("container-2", restored.getContainerId());
        assertEquals(cluster.getProxyPort(), restored.getPort());
        assertEquals("available", service.getDbInstance("c-1").getStatus());
        assertEquals(cluster.getProxyPort(), service.getDbInstance("c-1").getPort());
    }

    @Test
    void aClusterComesBackInTheEngineItsGraphWasKeptIn() {
        NeptuneCluster gremlin = service.createDbCluster("gremlin", null, false);
        NeptuneCluster older = service.createDbCluster("older", null, false);
        older.setDbType(null);
        dbType = "neo4j";
        clearInvocations(containers);

        restarted().restorePersistedRuntime().join();

        verify(containers).tryStart("gremlin", GREMLIN_IMAGE, NeptuneDbType.GREMLIN, ACCOUNT, REGION,
                gremlin.getVolumeId());
        verify(containers).tryStart("older", NEO4J_IMAGE, NeptuneDbType.NEO4J, ACCOUNT, REGION, older.getVolumeId());
        assertEquals(NeptuneDbType.NEO4J, service.getDbCluster("older").getDbType());
    }

    @Test
    void aClusterIsInMaintenanceAndItsInstancesStartingWhileItsContainerComesBack() throws Exception {
        service.createDbCluster("c", null, false);
        service.createDbInstance("c-1", "c", null, null, false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockStarts(starting, release);

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        assertEquals("maintenance", service.getDbCluster("c").getStatus());
        assertEquals("starting", service.getDbInstance("c-1").getStatus());
        release.countDown();
        restore.join();

        assertEquals("available", service.getDbCluster("c").getStatus());
        assertEquals("available", service.getDbInstance("c-1").getStatus());
    }

    @Test
    void withNoDockerDaemonAClusterComesBackWithoutAContainer() {
        service.createDbCluster("c", null, false);
        service.createDbInstance("c-1", "c", null, null, false);
        doReturn(null).when(containers).tryStart(anyString(), anyString(), any(), anyString(), anyString(), any());
        clearInvocations(proxies);

        service.restorePersistedRuntime().join();

        assertEquals("available", service.getDbCluster("c").getStatus());
        assertNull(service.getDbCluster("c").getContainerId());
        assertEquals("available", service.getDbInstance("c-1").getStatus());
        verify(proxies, never()).startProxy(anyString(), anyInt(), anyString(), anyInt());
    }

    @Test
    void aClusterWhoseContainerDoesNotStartIsLeftWithoutOneAndTriedAgainAtTheNextStart() {
        service.createDbCluster("bad", null, false);
        service.createDbInstance("bad-1", "bad", null, null, false);
        service.createDbCluster("good", null, false);
        doThrow(new IllegalStateException("no such image"))
                .when(containers).tryStart(eq("bad"), anyString(), any(), anyString(), anyString(), any());

        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.restorePersistedRuntime().join());

        assertTrue(failure.getCause().getMessage().contains("bad"), failure.getCause().getMessage());
        assertEquals("available", service.getDbCluster("good").getStatus());
        assertNull(service.getDbCluster("bad").getContainerId());
        assertEquals("failed", service.getDbInstance("bad-1").getStatus());
        verify(containers).stopByClusterId(identity("bad"));

        startsContainers();
        service.restorePersistedRuntime().join();
        assertNotNull(service.getDbCluster("bad").getContainerId());
        assertEquals("available", service.getDbInstance("bad-1").getStatus());
    }

    @Test
    void aClusterWhoseProxyDoesNotStartHasItsNewContainerRemoved() {
        service.createDbCluster("c", null, false);
        service.createDbInstance("c-1", "c", null, null, false);
        doThrow(new IllegalStateException("port in use"))
                .when(proxies).startProxy(anyString(), anyInt(), anyString(), anyInt());

        assertThrows(CompletionException.class, () -> service.restorePersistedRuntime().join());

        verify(containers).stopByClusterId(identity("c"));
        assertNull(service.getDbCluster("c").getContainerId());
        assertEquals("failed", service.getDbInstance("c-1").getStatus());
    }

    @Test
    void aStoredClustersPortIsHeldForItFromTheStart() throws Exception {
        int stored = service.createDbCluster("stored", null, false).getProxyPort();
        NeptuneService restarted = restarted();
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockStarts(starting, release);
        doReturn(new NeptuneContainerHandle("container-new", identity("new"), "localhost", 49999))
                .when(containers).tryStart(eq("new"), anyString(), any(), anyString(), anyString(), any());

        CompletableFuture<Void> restore = restarted.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        int created = restarted.createDbCluster("new", null, false).getProxyPort();
        release.countDown();
        restore.join();

        assertNotEquals(stored, created);
        assertEquals(stored, restarted.getDbCluster("stored").getProxyPort());
    }

    @Test
    void aClusterWhosePortAnotherHoldsMovesToAFreeOne() {
        NeptuneCluster first = service.createDbCluster("first", null, false);
        NeptuneCluster second = service.createDbCluster("second", null, false);
        service.createDbInstance("first-1", "first", null, null, false);
        service.createDbInstance("second-1", "second", null, null, false);
        second.setProxyPort(first.getProxyPort());
        second.setPort(first.getProxyPort());
        clearInvocations(proxies);

        restarted().restorePersistedRuntime().join();

        assertNotEquals(service.getDbCluster("first").getProxyPort(), service.getDbCluster("second").getProxyPort());
        for (String id : List.of("first", "second")) {
            NeptuneCluster cluster = service.getDbCluster(id);
            assertEquals(cluster.getProxyPort(), cluster.getPort(), id);
            assertEquals(cluster.getProxyPort(), service.getDbInstance(id + "-1").getPort(), id);
            verify(proxies).startProxy(eq(identity(id)), eq(cluster.getProxyPort()), anyString(), anyInt());
        }
    }

    @Test
    void aRestoreThatFailsOnceItsProxyHasStartedStopsItAndItsContainer() {
        service.createDbCluster("c", null, false);
        service.createDbInstance("c-1", "c", null, null, false);
        refuse = "c:available";
        clearInvocations(proxies, containers);

        assertThrows(CompletionException.class, () -> service.restorePersistedRuntime().join());

        verify(proxies).startProxy(eq(identity("c")), anyInt(), anyString(), anyInt());
        verify(proxies).stopProxy(identity("c"));
        verify(containers).stopByClusterId(identity("c"));
        assertNull(service.getDbCluster("c").getContainerId());
        assertEquals("failed", service.getDbInstance("c-1").getStatus());
    }

    @Test
    void anInstanceMovedToAnotherClusterDuringTheRestoreKeepsThatClustersPort() throws Exception {
        service.createDbCluster("c", null, false);
        service.createDbInstance("c-1", "c", null, null, false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockStarts(starting, release);
        doReturn(new NeptuneContainerHandle("container-d", identity("d"), "localhost", 49999))
                .when(containers).tryStart(eq("d"), anyString(), any(), anyString(), anyString(), any());

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        int dPort = service.createDbCluster("d", null, false).getProxyPort();
        onInstanceRead = () -> {
            service.deleteDbInstance("c-1");
            service.createDbInstance("c-1", "d", null, null, false);
        };
        release.countDown();
        restore.join();

        assertEquals("d", service.getDbInstance("c-1").getDbClusterIdentifier());
        assertEquals(dPort, service.getDbInstance("c-1").getPort());
    }

    @Test
    void aClusterRecordedBeforeItsGraphWasKeptGetsAVolumeKeptEvenWhenTheStartFails() {
        NeptuneCluster cluster = service.createDbCluster("c", null, false);
        cluster.setVolumeId(null);
        doThrow(new IllegalStateException("no such image"))
                .when(containers).tryStart(anyString(), anyString(), any(), anyString(), anyString(), any());
        clearInvocations(containers);

        assertThrows(CompletionException.class, () -> service.restorePersistedRuntime().join());

        String given = service.getDbCluster("c").getVolumeId();
        assertNotNull(given);
        verify(containers).tryStart(eq("c"), anyString(), any(), anyString(), anyString(), eq(given));
    }

    @Test
    void aDeleteFlociDidNotLiveToFinishIsFinishedInTheBackground() {
        NeptuneCluster cluster = service.createDbCluster("gone", null, false);
        cluster.setStatus("deleting");
        NeptuneService restarted = restarted();
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return null;
        }).when(containers).stop(any());
        clearInvocations(containers);

        CompletableFuture<Void> restore = assertTimeoutPreemptively(Duration.ofSeconds(5),
                restarted::restorePersistedRuntime);
        release.countDown();
        restore.join();

        assertFalse(restarted.hasCluster("gone"));
        verify(containers).stop(argThat(handle -> cluster.getContainerId().equals(handle.getContainerId())));
        verify(containers).removeStorage(cluster.getVolumeId());
        verify(containers, never()).tryStart(anyString(), anyString(), any(), anyString(), anyString(), any());
        assertEquals(cluster.getProxyPort(), restarted.createDbCluster("next", null, false).getProxyPort());
    }

    @Test
    void aClusterDeletedDuringItsRestoreGoesOnceItsContainerHasStarted() throws Exception {
        NeptuneCluster cluster = service.createDbCluster("c", null, false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockStarts(starting, release);
        clearInvocations(proxies);

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        service.deleteDbCluster("c");
        service.deleteDbCluster("c");
        assertEquals("deleting", service.getDbCluster("c").getStatus());
        verify(containers, never()).removeStorage(any());
        release.countDown();
        restore.join();

        assertFalse(service.hasCluster("c"));
        verify(containers).stop(argThat(handle -> "container-2".equals(handle.getContainerId())));
        verify(containers).removeStorage(cluster.getVolumeId());
        verify(proxies, never()).startProxy(anyString(), anyInt(), anyString(), anyInt());
    }

    @Test
    void aClusterDeletedWhileItsStartFailsIsStillRemoved() throws Exception {
        service.createDbCluster("c", null, false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            starting.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            throw new IllegalStateException("no such image");
        }).when(containers).tryStart(anyString(), anyString(), any(), anyString(), anyString(), any());

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        service.deleteDbCluster("c");
        release.countDown();

        assertThrows(CompletionException.class, restore::join);
        assertFalse(service.hasCluster("c"));
    }

    @Test
    void noInstanceJoinsAClusterWhileItsContainerComesBackOrItWaitsToBeDeleted() throws Exception {
        service.createDbCluster("c", null, false);
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
        assertFalse(service.hasInstance("c-1"));
    }

    @Test
    void aClusterReplacedDuringItsRestoreKeepsItsOwnRecord() throws Exception {
        NeptuneCluster cluster = service.createDbCluster("c", null, false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockStarts(starting, release);

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        NeptuneCluster replacement = new NeptuneCluster();
        replacement.setDbClusterIdentifier("c");
        replacement.setDbClusterArn(cluster.getDbClusterArn());
        replacement.setDbClusterResourceId("cluster-REPLACEMENT");
        replacement.setStatus("available");
        replacement.setContainerId("replacement-container");
        clusters().putForAccount(ACCOUNT, REGION + "/c", replacement);
        release.countDown();
        restore.join();

        assertEquals("replacement-container", service.getDbCluster("c").getContainerId());
        verify(containers).stop(argThat(handle -> "container-2".equals(handle.getContainerId())));
    }

    @Test
    void aShutdownDuringTheRestoreLeavesTheClustersForTheNextStart() {
        service.createDbCluster("first", null, false);
        service.createDbInstance("first-1", "first", null, null, false);
        service.createDbCluster("second", null, false);
        service.createDbInstance("second-1", "second", null, null, false);
        clearInvocations(containers);
        doAnswer(inv -> {
            shuttingDown.set(true);
            throw new IllegalStateException("Neptune is shutting down");
        }).when(containers).tryStart(anyString(), anyString(), any(), anyString(), anyString(), any());

        service.restorePersistedRuntime().join();

        verify(containers, times(1)).tryStart(anyString(), anyString(), any(), anyString(), anyString(), any());
        for (String id : List.of("first", "second")) {
            assertEquals("maintenance", service.getDbCluster(id).getStatus());
            assertEquals("starting", service.getDbInstance(id + "-1").getStatus());
        }
    }

    @Test
    void aClusterThatCannotBeMarkedIsReportedWithoutStoppingStartupOrTheOthers() {
        service.createDbCluster("broken", null, false);
        service.createDbCluster("good", null, false);
        refuseMarking = "broken";
        clearInvocations(containers);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.restorePersistedRuntime().join());

        assertTrue(failure.getCause().getMessage().contains("broken"), failure.getCause().getMessage());
        assertEquals("available", service.getDbCluster("good").getStatus());
        verify(containers, never()).tryStart(eq("broken"), anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void aRestoreInTheSameProcessKeepsEachClustersPort() {
        int port = service.createDbCluster("c", null, false).getProxyPort();

        service.restorePersistedRuntime().join();

        assertEquals(port, service.getDbCluster("c").getProxyPort());
        verify(proxies).startProxy(identity("c"), port, "localhost", 40002);
    }

    @Test
    void aDeleteLeftDeletingWithNoRestoreRunningIsRetriedAtOnce() {
        NeptuneCluster cluster = service.createDbCluster("c", null, false);
        cluster.setStatus("deleting");

        service.deleteDbCluster("c");

        assertFalse(service.hasCluster("c"));
        verify(containers).removeStorage(cluster.getVolumeId());
    }

    @Test
    void anInstanceDeletedWhileTheRestoreWritesItsStatusStaysDeleted() throws Exception {
        service.createDbCluster("c", null, false);
        NeptuneInstance created = service.createDbInstance("c-1", "c", null, null, false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockStarts(starting, release);
        Thread[] deleter = new Thread[1];
        // The restore read this instance and is setting its status, between the read and the write.
        StatusHookedInstance instance = new StatusHookedInstance(created, () -> {
            deleter[0] = new Thread(() -> service.deleteDbInstance("c-1"));
            deleter[0].start();
            // The delete either waits for the instance the restore is writing, or, with nothing to stop it, is done.
            await().atMost(Duration.ofSeconds(10)).until(() -> deleter[0].getState() == Thread.State.BLOCKED
                    || deleter[0].getState() == Thread.State.TERMINATED);
        });
        instances().putForAccount(ACCOUNT, REGION + "/c-1", instance);

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        instance.arm();
        release.countDown();
        restore.join();
        deleter[0].join(10_000);

        assertFalse(service.hasInstance("c-1"));
    }

    /** An instance that runs a hook, once armed, when the restore makes it available. */
    private static final class StatusHookedInstance extends NeptuneInstance {
        private final Runnable hook;
        private volatile boolean armed;

        StatusHookedInstance(NeptuneInstance from, Runnable hook) {
            this.hook = hook;
            setDbInstanceIdentifier(from.getDbInstanceIdentifier());
            setDbClusterIdentifier(from.getDbClusterIdentifier());
            setDbInstanceArn(from.getDbInstanceArn());
            setEndpoint(from.getEndpoint());
            setPort(from.getPort());
            super.setStatus(from.getStatus());
        }

        void arm() {
            armed = true;
        }

        @Override
        public void setStatus(String status) {
            super.setStatus(status);
            if (armed && "available".equals(status)) {
                armed = false;
                hook.run();
            }
        }
    }

    @Test
    void endingARestoreAnyWayLeavesLaterDeletesToRemoveTheClusterAtOnce() throws Exception {
        service.createDbCluster("gone", null, false);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockStarts(starting, release);

        CompletableFuture<Void> restore = service.restorePersistedRuntime();
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        clusters().deleteForAccount(ACCOUNT, REGION + "/gone");
        release.countDown();
        restore.join();
        startsContainers();
        service.createDbCluster("gone", null, false);
        service.deleteDbCluster("gone");

        assertFalse(service.hasCluster("gone"));
    }

    @Test
    void aClusterWhoseInstancesCannotBeMarkedLeavesLaterDeletesToRemoveItAtOnce() {
        service.createDbCluster("c", null, false);
        service.createDbInstance("c-1", "c", null, null, false);
        refuseInstance = "c-1:starting";
        clearInvocations(containers);

        assertThrows(CompletionException.class, () -> service.restorePersistedRuntime().join());
        service.deleteDbInstance("c-1");
        service.deleteDbCluster("c");

        assertFalse(service.hasCluster("c"));
        verify(containers, never()).tryStart(anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void aShutdownDuringTheRestoreLeavesLaterDeletesToRemoveTheClustersAtOnce() {
        service.createDbCluster("first", null, false);
        service.createDbCluster("second", null, false);
        doAnswer(inv -> {
            shuttingDown.set(true);
            throw new IllegalStateException("Neptune is shutting down");
        }).when(containers).tryStart(anyString(), anyString(), any(), anyString(), anyString(), any());

        service.restorePersistedRuntime().join();
        service.deleteDbCluster("first");
        service.deleteDbCluster("second");

        assertFalse(service.hasCluster("first"));
        assertFalse(service.hasCluster("second"));
    }

    @Test
    void deletesAfterARestoreOrAFailedOneRemoveTheClusterAtOnce() {
        service.createDbCluster("restored", null, false);
        service.createDbCluster("failed", null, false);
        doThrow(new IllegalStateException("no such image"))
                .when(containers).tryStart(eq("failed"), anyString(), any(), anyString(), anyString(), any());
        assertThrows(CompletionException.class, () -> service.restorePersistedRuntime().join());

        service.deleteDbCluster("restored");
        service.deleteDbCluster("failed");

        assertFalse(service.hasCluster("restored"));
        assertFalse(service.hasCluster("failed"));
    }

    /** A service over the same stores, as Floci reads them back after a restart, holding no ports yet. */
    private NeptuneService restarted() {
        service = new NeptuneService(config, regionResolver, containers, proxies, storageFactory);
        return service;
    }

    @SuppressWarnings("unchecked")
    private AccountAwareStorageBackend<NeptuneCluster> clusters() {
        return (AccountAwareStorageBackend<NeptuneCluster>) stores.get("neptune-clusters.json");
    }

    @SuppressWarnings("unchecked")
    private AccountAwareStorageBackend<NeptuneInstance> instances() {
        return (AccountAwareStorageBackend<NeptuneInstance>) stores.get("neptune-instances.json");
    }

    private static String identity(String id) {
        return ACCOUNT + "-" + REGION + "-" + id;
    }

    /** Each start gives a container of its own, numbered in start order, its port after it. */
    private void startsContainers() {
        doAnswer(inv -> {
            int n = started.incrementAndGet();
            return new NeptuneContainerHandle("container-" + n, inv.getArgument(0), "localhost", 40000 + n);
        }).when(containers).tryStart(anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    /** Starts that signal {@code starting} and wait for {@code release} before giving their container. */
    private void blockStarts(CountDownLatch starting, CountDownLatch release) {
        doAnswer(inv -> {
            starting.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            int n = started.incrementAndGet();
            return new NeptuneContainerHandle("container-" + n, inv.getArgument(0), "localhost", 40000 + n);
        }).when(containers).tryStart(anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    /** The cluster store, which a test can have refuse to mark a cluster for the restore. */
    private final class ClusterStore extends InMemoryStorage<String, NeptuneCluster> {
        @Override
        public synchronized void put(String key, NeptuneCluster value) {
            if ("maintenance".equals(value.getStatus()) && value.getDbClusterIdentifier().equals(refuseMarking)) {
                throw new IllegalStateException("the cluster store refused the write");
            }
            if ((value.getDbClusterIdentifier() + ":" + value.getStatus()).equals(refuse)) {
                refuse = null;
                throw new IllegalStateException("the cluster store refused the write");
            }
            super.put(key, value);
        }
    }

    /** The instance store, which a test can have refuse a write or act inside a read of. */
    private final class InstanceStore extends InMemoryStorage<String, NeptuneInstance> {
        @Override
        public synchronized void put(String key, NeptuneInstance value) {
            if ((value.getDbInstanceIdentifier() + ":" + value.getStatus()).equals(refuseInstance)) {
                refuseInstance = null;
                throw new IllegalStateException("the instance store refused the write");
            }
            super.put(key, value);
        }

        @Override
        public Optional<NeptuneInstance> get(String key) {
            Runnable hook = onInstanceRead;
            if (hook != null && key.endsWith(REGION + "/c-1")) {
                onInstanceRead = null;
                hook.run();
            }
            return super.get(key);
        }
    }
}

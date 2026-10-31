package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.AutomatedSnapshotState;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import io.github.hectorvent.floci.services.redshift.model.SnapshotSchedule;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedshiftAutomatedSnapshotServiceTest {
    private static final String ACCOUNT = "111111111111";
    private static final Instant ANCHOR = Instant.parse("2026-10-25T00:00:00Z");

    @TempDir
    Path directory;

    @Test
    void rateSnapshotsAndCheckpointsSurviveRestartWithoutDuplicateDumps() throws Exception {
        Fixture first = fixture();
        seed(first, ACCOUNT, "warehouse", "us-east-1", "rate(1 hour)");
        first.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));
        first.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));
        Snapshot snapshot = first.snapshots().scanForAccount(ACCOUNT, key -> true).getFirst();
        assertEquals("automated", snapshot.getSnapshotType());
        assertEquals("backup contents", Files.readString(Path.of(snapshot.getSqlDump())));
        verify(first.manager()).takeSnapshot(eq(ACCOUNT), eq("warehouse"), eq("admin"), any(Path.class));

        Fixture restarted = fixture();
        restarted.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));
        verifyNoInteractions(restarted.manager());
        restarted.service().runSnapshotSchedules(ANCHOR.plusSeconds(6 * 3600));
        assertEquals(2, restarted.snapshots().scanForAccount(ACCOUNT, key -> true).size());
        verify(restarted.manager()).takeSnapshot(eq(ACCOUNT), eq("warehouse"), eq("admin"), any(Path.class));
    }

    @Test
    void delayedTicksDoNotCreateTwoBackupsWithinOneHour() {
        Fixture fixture = fixture();
        seed(fixture, ACCOUNT, "warehouse", "us-east-1", "rate(1 hour)");
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(7199));
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(7200));
        assertEquals(1, fixture.snapshots().scanForAccount(ACCOUNT, key -> true).size());
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(10799));
        assertEquals(2, fixture.snapshots().scanForAccount(ACCOUNT, key -> true).size());
    }

    @Test
    void backgroundSweepKeepsAccountsRegionsAndPartitionsSeparate() {
        Fixture fixture = fixture();
        seed(fixture, ACCOUNT, "same-name", "cn-north-1", "rate(1 hour)");
        seed(fixture, "222222222222", "same-name", "us-gov-west-1", "rate(1 hour)");

        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));

        Snapshot china = fixture.snapshots().scanForAccount(ACCOUNT, key -> true).getFirst();
        Snapshot gov = fixture.snapshots().scanForAccount("222222222222", key -> true).getFirst();
        assertTrue(china.getSnapshotArn().startsWith("arn:aws-cn:redshift:cn-north-1:" + ACCOUNT + ":"));
        assertTrue(gov.getSnapshotArn().startsWith("arn:aws-us-gov:redshift:us-gov-west-1:222222222222:"));
        assertTrue(Path.of(china.getSqlDump()).startsWith(directory.resolve("redshift-dumps").resolve(ACCOUNT)));
        assertTrue(Path.of(gov.getSqlDump()).startsWith(directory.resolve("redshift-dumps").resolve("222222222222")));
        assertNotEquals(china.getSqlDump(), gov.getSqlDump());
    }

    @Test
    void retentionPreservesTheLatestAutomatedSnapshotAndEveryManualSnapshot() throws Exception {
        Fixture fixture = fixture();
        Cluster cluster = seed(fixture, ACCOUNT, "warehouse", "us-east-1", "rate(1 hour)");
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));
        Snapshot first = fixture.snapshots().scanForAccount(ACCOUNT, key -> true).getFirst();
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(7200));
        Snapshot latest = fixture.snapshots().scanForAccount(ACCOUNT, key -> true).stream()
                .filter(snapshot -> !snapshot.getSnapshotIdentifier().equals(first.getSnapshotIdentifier()))
                .findFirst().orElseThrow();
        Snapshot manual = fixture.service().createSnapshot("manual-backup", "warehouse");
        cluster.setClusterStatus("paused");
        fixture.clusters().putForAccount(ACCOUNT, "warehouse", cluster);

        fixture.service().runSnapshotSchedules(ANCHOR.plus(Duration.ofDays(3)));

        assertTrue(fixture.snapshots().getForAccount(ACCOUNT, first.getSnapshotIdentifier()).isEmpty());
        assertFalse(Files.exists(Path.of(first.getSqlDump())));
        assertTrue(Files.exists(Path.of(latest.getSqlDump())));
        assertTrue(Files.exists(Path.of(manual.getSqlDump())));
        assertEquals(2, fixture.snapshots().scanForAccount(ACCOUNT, key -> true).size());
        assertEquals("InvalidClusterSnapshotState", assertThrows(AwsException.class,
                () -> fixture.service().deleteSnapshot(latest.getSnapshotIdentifier())).getErrorCode());

        cluster.setAutomatedSnapshotRetentionPeriod(0);
        fixture.service().runSnapshotSchedules(ANCHOR.plus(Duration.ofDays(4)));
        assertEquals(List.of(manual.getSnapshotIdentifier()), fixture.snapshots().scanForAccount(ACCOUNT, key -> true)
                .stream().map(Snapshot::getSnapshotIdentifier).toList());
    }

    @Test
    void missingClustersLoseAutomatedSnapshotsButKeepManualBackups() {
        Fixture fixture = fixture();
        seed(fixture, ACCOUNT, "warehouse", "us-east-1", "rate(1 hour)");
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));
        fixture.service().createSnapshot("manual-backup", "warehouse");
        fixture.clusters().deleteForAccount(ACCOUNT, "warehouse");

        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(7200));

        assertEquals(List.of("manual-backup"), fixture.snapshots().scanForAccount(ACCOUNT, key -> true).stream()
                .map(Snapshot::getSnapshotIdentifier).toList());
        assertTrue(fixture.states().getForAccount(ACCOUNT, "warehouse").isEmpty());
    }

    @Test
    void occurrenceIsRetakenWhenItsSnapshotMetadataWasLostButTheCheckpointSurvived() {
        Fixture fixture = fixture();
        seed(fixture, ACCOUNT, "warehouse", "us-east-1", "rate(1 hour)");
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));
        Snapshot lost = fixture.snapshots().scanForAccount(ACCOUNT, key -> true).getFirst();
        fixture.snapshots().deleteForAccount(ACCOUNT, lost.getSnapshotIdentifier());

        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));

        assertEquals(List.of(lost.getSnapshotIdentifier()), fixture.snapshots().scanForAccount(ACCOUNT, key -> true)
                .stream().map(Snapshot::getSnapshotIdentifier).toList());
        verify(fixture.manager(), times(2)).takeSnapshot(eq(ACCOUNT), eq("warehouse"), eq("admin"), any(Path.class));
    }

    @Test
    void retentionStillRunsWhenTheNextDumpFails() throws Exception {
        Fixture fixture = fixture();
        seed(fixture, ACCOUNT, "warehouse", "us-east-1", "rate(1 hour)");
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));
        Snapshot first = fixture.snapshots().scanForAccount(ACCOUNT, key -> true).getFirst();
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(7200));
        Snapshot latest = fixture.snapshots().scanForAccount(ACCOUNT, key -> true).stream()
                .filter(snapshot -> !snapshot.getSnapshotIdentifier().equals(first.getSnapshotIdentifier()))
                .findFirst().orElseThrow();
        doThrow(new IllegalStateException("dump failed")).when(fixture.manager())
                .takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));

        fixture.service().runSnapshotSchedules(ANCHOR.plus(Duration.ofDays(3)));

        assertTrue(fixture.snapshots().getForAccount(ACCOUNT, first.getSnapshotIdentifier()).isEmpty());
        assertFalse(Files.exists(Path.of(first.getSqlDump())));
        assertTrue(fixture.snapshots().getForAccount(ACCOUNT, latest.getSnapshotIdentifier()).isPresent());
    }

    @Test
    void recreatedClusterDoesNotKeepThePreviousInstancesAutomatedBackups() {
        Fixture fixture = fixture();
        Cluster original = seed(fixture, ACCOUNT, "warehouse", "us-east-1", "rate(1 hour)");
        original.setSnapshotGeneration("first-instance");
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));
        assertEquals(1, fixture.snapshots().scanForAccount(ACCOUNT, key -> true).size());
        Cluster replacement = seed(fixture, ACCOUNT, "warehouse", "us-east-1", "rate(1 hour)");
        replacement.setSnapshotGeneration("replacement-instance");
        replacement.setClusterStatus("creating");

        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3601));

        assertTrue(fixture.snapshots().scanForAccount(ACCOUNT, key -> true).isEmpty());
    }

    @Test
    void defaultScheduleAndLegacyAnchorAreStableAfterRestart() {
        Fixture fixture = fixture();
        Cluster cluster = seed(fixture, ACCOUNT, "warehouse", "us-east-1", null);
        cluster.setAutomatedSnapshotAnchor(null);
        fixture.clusters().putForAccount(ACCOUNT, "warehouse", cluster);
        fixture.service().runSnapshotSchedules(ANCHOR);
        assertTrue(fixture.snapshots().scanForAccount(ACCOUNT, key -> true).isEmpty());

        Fixture restarted = fixture();
        restarted.service().runSnapshotSchedules(ANCHOR.plusSeconds(8 * 3600));

        assertEquals(1, restarted.snapshots().scanForAccount(ACCOUNT, key -> true).size());
    }

    @Test
    void failedDumpIsCleanedUpAndTheSameOccurrenceCanRetry() throws Exception {
        Fixture fixture = fixture();
        seed(fixture, ACCOUNT, "warehouse", "us-east-1", "rate(1 hour)");
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(3, Path.class), "partial dump");
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("dump failed");
            }
            return null;
        }).when(fixture.manager()).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));

        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));

        assertTrue(fixture.snapshots().scanForAccount(ACCOUNT, key -> true).isEmpty());
        try (Stream<Path> files = Files.list(directory.resolve("redshift-dumps").resolve(ACCOUNT))) {
            assertEquals(0, files.count());
        }
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));
        assertEquals(1, fixture.snapshots().scanForAccount(ACCOUNT, key -> true).size());
        assertEquals(2, attempts.get());
    }

    @Test
    void committedDumpIsReusedWhenCheckpointWriteFailed() {
        Fixture fixture = fixture();
        seed(fixture, ACCOUNT, "warehouse", "us-east-1", "rate(1 hour)");
        doAnswer(invocation -> {
            Map<String, AutomatedSnapshotState> checkpoint = invocation.getArgument(1);
            AutomatedSnapshotState state = checkpoint.get("warehouse");
            if (state.lastScheduledTime() != null) {
                throw new IllegalStateException("checkpoint write failed");
            }
            return invocation.callRealMethod();
        }).when(fixture.states()).putAllAtomicallyForAccount(eq(ACCOUNT), anyMap());
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));
        assertEquals(1, fixture.snapshots().scanForAccount(ACCOUNT, key -> true).size());

        Fixture restarted = fixture();
        restarted.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));

        verifyNoInteractions(restarted.manager());
        assertEquals(ANCHOR.plusSeconds(3600), restarted.states().getForAccount(ACCOUNT, "warehouse")
                .orElseThrow().lastScheduledTime());
    }

    @Test
    void scheduleChangesResetTheAnchorAndDoNotReplayOldOccurrences() {
        Fixture fixture = fixture();
        seed(fixture, ACCOUNT, "warehouse", "us-east-1", "rate(1 hour)");
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));
        SnapshotSchedule changed = fixture.service().modifySnapshotSchedule("schedule-warehouse", List.of("rate(2 hours)"));
        assertNotNull(changed.getUpdatedAt());
        Instant activation = ANCHOR.plusSeconds(7200);
        changed.setUpdatedAt(activation);
        fixture.schedules().putForAccount(ACCOUNT, changed.getScheduleIdentifier(), changed);

        fixture.service().runSnapshotSchedules(activation.plusSeconds(7199));
        assertEquals(1, fixture.snapshots().scanForAccount(ACCOUNT, key -> true).size());
        fixture.service().runSnapshotSchedules(activation.plusSeconds(7200));
        assertEquals(2, fixture.snapshots().scanForAccount(ACCOUNT, key -> true).size());
    }

    @Test
    void failedAssociationPreservesTheExistingAnchorAndCheckpoint() {
        Fixture fixture = fixture();
        Cluster cluster = seed(fixture, ACCOUNT, "warehouse", "us-east-1", "rate(1 hour)");
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));
        AutomatedSnapshotState checkpoint = fixture.states().getForAccount(ACCOUNT, "warehouse").orElseThrow();

        for (String scheduleId : List.of("missing", "")) {
            assertThrows(AwsException.class,
                    () -> fixture.service().modifyClusterSnapshotSchedule("warehouse", scheduleId, false));
            assertEquals(ANCHOR, cluster.getAutomatedSnapshotAnchor());
            assertEquals("schedule-warehouse", cluster.getSnapshotScheduleIdentifier());
            assertEquals(checkpoint, fixture.states().getForAccount(ACCOUNT, "warehouse").orElseThrow());
        }
    }

    @Test
    void sweepCannotReadNewRulesWithTheOldActivationTime() throws Exception {
        Fixture fixture = fixture();
        Instant activation = Instant.now().minusSeconds(7200);
        Cluster cluster = seed(fixture, ACCOUNT, "warehouse", "us-east-1", "rate(24 hours)");
        cluster.setAutomatedSnapshotAnchor(activation);
        SnapshotSchedule schedule = spy(fixture.schedules().getForAccount(ACCOUNT, "schedule-warehouse").orElseThrow());
        schedule.setUpdatedAt(activation);
        fixture.schedules().putForAccount(ACCOUNT, "schedule-warehouse", schedule);
        CountDownLatch betweenSetters = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            betweenSetters.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(schedule).setUpdatedAt(any());
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> updating = executor.submit(() -> fixture.service().modifySnapshotSchedule(
                    "schedule-warehouse", List.of("rate(1 hour)")));
            try {
                Awaitility.await().atMost(Duration.ofSeconds(5))
                        .until(() -> betweenSetters.getCount() == 0 || updating.isDone());
                fixture.service().runSnapshotSchedules(Instant.now().plusSeconds(1800));
                assertTrue(fixture.snapshots().scanForAccount(ACCOUNT, key -> true).isEmpty());
            } finally {
                release.countDown();
            }
            updating.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void failedRetentionCommitPreservesMetadataAndDumpForRetry() throws Exception {
        Fixture fixture = fixture();
        Cluster cluster = seed(fixture, ACCOUNT, "warehouse", "us-east-1", "rate(1 hour)");
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600));
        Snapshot old = fixture.snapshots().scanForAccount(ACCOUNT, key -> true).getFirst();
        fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(7200));
        cluster.setClusterStatus("paused");
        Path metadata = directory.resolve("redshift-snapshots.json");
        Path saved = directory.resolve("snapshots-before-failure.json");
        Files.move(metadata, saved);
        Files.createDirectory(metadata);
        Files.writeString(metadata.resolve("block-replacement"), "keep");
        try {
            fixture.service().runSnapshotSchedules(ANCHOR.plus(Duration.ofDays(3)));

            assertTrue(fixture.snapshots().getForAccount(ACCOUNT, old.getSnapshotIdentifier()).isPresent());
            assertTrue(Files.exists(Path.of(old.getSqlDump())));
        } finally {
            Files.delete(metadata.resolve("block-replacement"));
            Files.delete(metadata);
            Files.move(saved, metadata);
        }
        fixture.service().runSnapshotSchedules(ANCHOR.plus(Duration.ofDays(3)));
        assertTrue(fixture.snapshots().getForAccount(ACCOUNT, old.getSnapshotIdentifier()).isEmpty());
        assertFalse(Files.exists(Path.of(old.getSqlDump())));
        assertEquals(1, fixture().snapshots().scanForAccount(ACCOUNT, key -> true).size());
    }

    @Test
    void concurrentSweepsTakeOnlyOneDumpForTheSameOccurrence() throws Exception {
        Fixture fixture = fixture();
        seed(fixture, ACCOUNT, "warehouse", "us-east-1", "rate(1 hour)");
        CountDownLatch dumping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            dumping.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            Files.writeString(invocation.getArgument(3, Path.class), "backup contents");
            return null;
        }).when(fixture.manager()).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600)));
            try {
                assertTrue(dumping.await(5, TimeUnit.SECONDS));
                Future<?> second = executor.submit(() -> fixture.service().runSnapshotSchedules(ANCHOR.plusSeconds(3600)));
                release.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        verify(fixture.manager()).takeSnapshot(eq(ACCOUNT), eq("warehouse"), eq("admin"), any(Path.class));
        assertEquals(1, fixture.snapshots().scanForAccount(ACCOUNT, key -> true).size());
    }

    private Fixture fixture() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("redshift")).thenReturn("persistent");
        StorageFactory factory = spy(new StorageFactory(config, access));
        AccountAwareStorageBackend<AutomatedSnapshotState> states = spy(factory.create("redshift",
                "redshift-automated-snapshot-state.json", new TypeReference<Map<String, AutomatedSnapshotState>>() { }));
        doReturn(states).when(factory).create(eq("redshift"), eq("redshift-automated-snapshot-state.json"), any());
        RedshiftContainerManager manager = mock(RedshiftContainerManager.class);
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(3, Path.class), "backup contents");
            return null;
        }).when(manager).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));
        RedshiftService service = new RedshiftService(factory, manager, config,
                new RegionResolver("us-east-1", ACCOUNT), mock(RedshiftProxyManager.class),
                mock(DockerHostResolver.class), new RedshiftCredentialBroker());
        return new Fixture(service, manager, states,
                factory.create("redshift", "redshift-clusters.json", new TypeReference<Map<String, Cluster>>() { }),
                factory.create("redshift", "redshift-snapshots.json", new TypeReference<Map<String, Snapshot>>() { }),
                factory.create("redshift", "redshift-snapshot-schedules.json", new TypeReference<Map<String, SnapshotSchedule>>() { }));
    }

    private static Cluster seed(Fixture fixture, String account, String id, String region, String definition) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(id);
        cluster.setNodeType("dc2.large");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123!");
        cluster.setClusterStatus("available");
        cluster.setSnapshotRegion(region);
        cluster.setAutomatedSnapshotAnchor(ANCHOR);
        if (definition != null) {
            SnapshotSchedule schedule = new SnapshotSchedule("schedule-" + id, null, List.of(definition));
            schedule.setUpdatedAt(ANCHOR);
            fixture.schedules().putForAccount(account, schedule.getScheduleIdentifier(), schedule);
            cluster.setSnapshotScheduleIdentifier(schedule.getScheduleIdentifier());
        }
        fixture.clusters().putForAccount(account, id, cluster);
        return cluster;
    }

    private record Fixture(RedshiftService service, RedshiftContainerManager manager,
                           AccountAwareStorageBackend<AutomatedSnapshotState> states,
                           AccountAwareStorageBackend<Cluster> clusters,
                           AccountAwareStorageBackend<Snapshot> snapshots,
                           AccountAwareStorageBackend<SnapshotSchedule> schedules) { }
}

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
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedshiftSnapshotCopyServiceTest {
    private static final String ACCOUNT = "111111111111";
    private static final String SOURCE = "us-east-1";
    private static final String DESTINATION = "us-west-2";

    @TempDir
    Path directory;

    @Test
    void copiesAnAvailableSnapshotIntoTheDestinationRegionOnce() throws Exception {
        Fixture fixture = fixture();
        seedCluster(fixture, "warehouse", Instant.now().minusSeconds(60));
        fixture.service(SOURCE).createSnapshot("nightly", "warehouse");

        fixture.service(SOURCE).runSnapshotCopies(Instant.now());
        fixture.service(SOURCE).runSnapshotCopies(Instant.now());

        Snapshot source = fixture.snapshots().getForAccount(ACCOUNT, "nightly").orElseThrow();
        assertTrue(source.getCopiedToRegions().contains(DESTINATION));
        Snapshot copy = fixture.copies().getForAccount(ACCOUNT, DESTINATION + "::nightly").orElseThrow();
        assertEquals("arn:aws:redshift:" + DESTINATION + ":" + ACCOUNT + ":snapshot:warehouse/nightly",
                copy.getSnapshotArn());
        assertEquals(SOURCE, copy.getSourceRegion());
        assertEquals(source.getSnapshotArn(), copy.getSourceSnapshotArn());
        assertEquals("backup contents", Files.readString(Path.of(copy.getSqlDump())));
        assertNotEquals(source.getSqlDump(), copy.getSqlDump());
        assertEquals(1, dumpFilesUnderCopies());
    }

    @Test
    void copyIsVisibleAndRestorableOnlyThroughTheDestinationRegion() {
        Fixture fixture = fixture();
        seedCluster(fixture, "warehouse", Instant.now().minusSeconds(60));
        fixture.service(SOURCE).createSnapshot("nightly", "warehouse");
        fixture.service(SOURCE).runSnapshotCopies(Instant.now());

        Snapshot atDestination = fixture.service(DESTINATION).describeSnapshots("nightly", null).getFirst();
        Snapshot atSource = fixture.service(SOURCE).describeSnapshots("nightly", null).getFirst();

        assertTrue(atDestination.getSnapshotArn().contains(":" + DESTINATION + ":"));
        assertTrue(atSource.getSnapshotArn().contains(":" + SOURCE + ":"));
        assertEquals(1, fixture.service(DESTINATION).describeSnapshots(null, "warehouse").size());
        assertEquals(atDestination.getSqlDump(), fixture.service(DESTINATION).getSnapshot("nightly")
                .orElseThrow().getSqlDump());
    }

    @Test
    void snapshotsTakenBeforeCopyWasEnabledStayWhereTheyAre() {
        Fixture fixture = fixture();
        seedCluster(fixture, "warehouse", Instant.now().plusSeconds(3600));
        fixture.service(SOURCE).createSnapshot("old", "warehouse");

        fixture.service(SOURCE).runSnapshotCopies(Instant.now());

        assertTrue(fixture.copies().scanForAccount(ACCOUNT, key -> true).isEmpty());
    }

    @Test
    void aCopyDeletedAtTheDestinationIsNotCopiedAgain() {
        Fixture fixture = fixture();
        seedCluster(fixture, "warehouse", Instant.now().minusSeconds(60));
        fixture.service(SOURCE).createSnapshot("nightly", "warehouse");
        fixture.service(SOURCE).runSnapshotCopies(Instant.now());
        Path copiedDump = Path.of(fixture.copies().getForAccount(ACCOUNT, DESTINATION + "::nightly")
                .orElseThrow().getSqlDump());

        fixture.service(DESTINATION).deleteSnapshot("nightly");
        fixture.service(SOURCE).runSnapshotCopies(Instant.now());

        assertTrue(fixture.copies().scanForAccount(ACCOUNT, key -> true).isEmpty());
        assertFalse(Files.exists(copiedDump));
        assertTrue(fixture.snapshots().getForAccount(ACCOUNT, "nightly").isPresent());
    }

    @Test
    void deletingTheSourceLeavesTheCopyInPlace() {
        Fixture fixture = fixture();
        seedCluster(fixture, "warehouse", Instant.now().minusSeconds(60));
        fixture.service(SOURCE).createSnapshot("nightly", "warehouse");
        fixture.service(SOURCE).runSnapshotCopies(Instant.now());

        fixture.service(SOURCE).deleteSnapshot("nightly");

        assertTrue(fixture.copies().getForAccount(ACCOUNT, DESTINATION + "::nightly").isPresent());
        assertEquals("nightly", fixture.service(DESTINATION).describeSnapshots("nightly", null)
                .getFirst().getSnapshotIdentifier());
        assertThrows(AwsException.class, () -> fixture.service(SOURCE).describeSnapshots("nightly", null));
    }

    @Test
    void copiedManualSnapshotsExpireAfterTheCopyRetentionAndLeaveTheSourceAlone() {
        Fixture fixture = fixture();
        Cluster cluster = seedCluster(fixture, "warehouse", Instant.now().minusSeconds(60));
        cluster.setSnapshotCopyManualRetentionPeriod(1);
        fixture.clusters().putForAccount(ACCOUNT, "warehouse", cluster);
        fixture.service(SOURCE).createSnapshot("nightly", "warehouse");
        fixture.service(SOURCE).runSnapshotCopies(Instant.now());
        Path copiedDump = Path.of(fixture.copies().getForAccount(ACCOUNT, DESTINATION + "::nightly")
                .orElseThrow().getSqlDump());

        fixture.service(SOURCE).runSnapshotCopies(Instant.now().plus(Duration.ofHours(12)));
        assertTrue(fixture.copies().getForAccount(ACCOUNT, DESTINATION + "::nightly").isPresent());

        fixture.service(SOURCE).runSnapshotCopies(Instant.now().plus(Duration.ofDays(2)));
        assertTrue(fixture.copies().getForAccount(ACCOUNT, DESTINATION + "::nightly").isEmpty());
        assertFalse(Files.exists(copiedDump));
        assertTrue(fixture.snapshots().getForAccount(ACCOUNT, "nightly").isPresent());
        assertTrue(Files.exists(Path.of(fixture.snapshots().getForAccount(ACCOUNT, "nightly")
                .orElseThrow().getSqlDump())));
    }

    @Test
    void aSnapshotWhoseDumpIsMissingIsLeftUncopiedAndRetried() throws IOException {
        Fixture fixture = fixture();
        seedCluster(fixture, "warehouse", Instant.now().minusSeconds(60));
        Snapshot snapshot = fixture.service(SOURCE).createSnapshot("nightly", "warehouse");
        Files.delete(Path.of(snapshot.getSqlDump()));

        fixture.service(SOURCE).runSnapshotCopies(Instant.now());

        assertTrue(fixture.copies().scanForAccount(ACCOUNT, key -> true).isEmpty());
        assertTrue(fixture.snapshots().getForAccount(ACCOUNT, "nightly").orElseThrow()
                .getCopiedToRegions().isEmpty());
    }

    @Test
    void aCopyCommittedBeforeTheSourceWasMarkedIsNotCopiedTwice() {
        Fixture fixture = fixture();
        seedCluster(fixture, "warehouse", Instant.now().minusSeconds(60));
        fixture.service(SOURCE).createSnapshot("nightly", "warehouse");
        fixture.service(SOURCE).runSnapshotCopies(Instant.now());
        Snapshot source = fixture.snapshots().getForAccount(ACCOUNT, "nightly").orElseThrow();
        Snapshot unmarked = new Snapshot(source);
        unmarked.getCopiedToRegions().clear();
        fixture.snapshots().putForAccount(ACCOUNT, "nightly", unmarked);
        String committedDump = fixture.copies().getForAccount(ACCOUNT, DESTINATION + "::nightly")
                .orElseThrow().getSqlDump();

        fixture.service(SOURCE).runSnapshotCopies(Instant.now());

        assertEquals(committedDump, fixture.copies().getForAccount(ACCOUNT, DESTINATION + "::nightly")
                .orElseThrow().getSqlDump());
        assertTrue(fixture.snapshots().getForAccount(ACCOUNT, "nightly").orElseThrow()
                .getCopiedToRegions().contains(DESTINATION));
        assertEquals(1, dumpFilesUnderCopies());
    }

    @Test
    void copiesKeepAccountsSeparate() {
        Fixture fixture = fixture();
        seedCluster(fixture, "warehouse", Instant.now().minusSeconds(60));
        fixture.service(SOURCE).createSnapshot("nightly", "warehouse");

        fixture.service(SOURCE).runSnapshotCopies(Instant.now());

        assertTrue(fixture.copies().scanForAccount("222222222222", key -> true).isEmpty());
        assertTrue(Path.of(fixture.copies().getForAccount(ACCOUNT, DESTINATION + "::nightly").orElseThrow()
                .getSqlDump()).startsWith(directory.resolve("redshift-dumps").resolve(ACCOUNT)));
    }

    @Test
    void enablingCopyIntoTheClustersOwnRegionIsRefused() {
        Fixture fixture = fixture();
        Cluster cluster = seedCluster(fixture, "warehouse", null);
        cluster.setSnapshotCopyDestinationRegion(null);
        fixture.clusters().putForAccount(ACCOUNT, "warehouse", cluster);

        AwsException refused = assertThrows(AwsException.class,
                () -> fixture.service(SOURCE).enableSnapshotCopy("warehouse", SOURCE, null, null, null));
        assertEquals("UnknownSnapshotCopyRegionFault", refused.getErrorCode());

        Cluster enabled = fixture.service(SOURCE).enableSnapshotCopy("warehouse", DESTINATION, null, null, null);
        assertNotNull(enabled.getSnapshotCopyEnabledAt());
    }

    @Test
    void aNewSnapshotCannotTakeTheIdentifierOfACopyInThatRegion() {
        Fixture fixture = fixture();
        seedCluster(fixture, "warehouse", Instant.now().minusSeconds(60));
        fixture.service(SOURCE).createSnapshot("nightly", "warehouse");
        fixture.service(SOURCE).runSnapshotCopies(Instant.now());
        fixture.service(SOURCE).deleteSnapshot("nightly");

        AwsException refused = assertThrows(AwsException.class,
                () -> fixture.service(DESTINATION).createSnapshot("nightly", "warehouse"));
        assertEquals("ClusterSnapshotAlreadyExists", refused.getErrorCode());
    }

    @Test
    void aRecreatedSourceDoesNotReplaceTheRetainedCopyOfAnEarlierSnapshot() throws Exception {
        Fixture fixture = fixture();
        seedCluster(fixture, "warehouse", Instant.now().minusSeconds(60));
        fixture.service(SOURCE).createSnapshot("nightly", "warehouse");
        fixture.service(SOURCE).runSnapshotCopies(Instant.now());
        String key = DESTINATION + "::nightly";
        Snapshot retained = new Snapshot(fixture.copies().getForAccount(ACCOUNT, key).orElseThrow());
        retained.setSourceSnapshotCreateTime(retained.getSourceSnapshotCreateTime().minusSeconds(30));
        retained.setTags(new LinkedHashMap<>(Map.of("env", "prod")));
        fixture.copies().putForAccount(ACCOUNT, key, retained);
        fixture.service(SOURCE).deleteSnapshot("nightly");
        fixture.service(SOURCE).createSnapshot("nightly", "warehouse");

        fixture.service(SOURCE).runSnapshotCopies(Instant.now());

        Snapshot kept = fixture.copies().getForAccount(ACCOUNT, key).orElseThrow();
        assertEquals(retained.getSqlDump(), kept.getSqlDump());
        assertEquals(Map.of("env", "prod"), kept.getTags());
        assertTrue(Files.exists(Path.of(kept.getSqlDump())));
        assertEquals(1, dumpFilesUnderCopies());
        assertTrue(fixture.snapshots().getForAccount(ACCOUNT, "nightly").orElseThrow()
                .getCopiedToRegions().isEmpty());
    }

    @Test
    void aCopyOfARemovedClusterKeepsItsOwnRetentionWhenTheNameIsReused() {
        Fixture fixture = fixture();
        Cluster cluster = seedCluster(fixture, "warehouse", Instant.now().minusSeconds(60));
        cluster.setSnapshotGeneration("gen-1");
        fixture.clusters().putForAccount(ACCOUNT, "warehouse", cluster);
        fixture.service(SOURCE).createSnapshot("nightly", "warehouse");
        fixture.service(SOURCE).runSnapshotCopies(Instant.now());
        String key = DESTINATION + "::nightly";
        Snapshot copy = new Snapshot(fixture.copies().getForAccount(ACCOUNT, key).orElseThrow());
        copy.setSnapshotType("automated");
        copy.setSnapshotCreateTime(Instant.now().minus(Duration.ofDays(3)));
        copy.setAutomatedSnapshotRetentionPeriod(7);
        fixture.copies().putForAccount(ACCOUNT, key, copy);
        cluster.setSnapshotGeneration("gen-2");
        cluster.setSnapshotCopyRetentionPeriod(1);
        fixture.clusters().putForAccount(ACCOUNT, "warehouse", cluster);

        fixture.service(SOURCE).runSnapshotCopies(Instant.now());
        assertTrue(fixture.copies().getForAccount(ACCOUNT, key).isPresent());

        cluster.setSnapshotGeneration("gen-1");
        fixture.clusters().putForAccount(ACCOUNT, "warehouse", cluster);
        fixture.service(SOURCE).runSnapshotCopies(Instant.now());
        assertTrue(fixture.copies().getForAccount(ACCOUNT, key).isEmpty());
    }

    @Test
    void describeTagsListsOnlyTheCopyTagsForASnapshotItShadows() {
        Fixture fixture = fixture();
        seedCluster(fixture, "warehouse", Instant.now().minusSeconds(60));
        fixture.service(SOURCE).createSnapshot("nightly", "warehouse");
        String sourceArn = "arn:aws:redshift:" + SOURCE + ":" + ACCOUNT + ":snapshot:warehouse/nightly";
        String copyArn = "arn:aws:redshift:" + DESTINATION + ":" + ACCOUNT + ":snapshot:warehouse/nightly";
        fixture.service(SOURCE).createTags(sourceArn, Map.of("env", "prod"));
        fixture.service(SOURCE).runSnapshotCopies(Instant.now());
        fixture.service(DESTINATION).createTags(copyArn, Map.of("env", "test"));

        List<RedshiftService.TaggedResource> tags = fixture.service(DESTINATION)
                .describeTags(null, "snapshot", null);

        assertEquals(List.of(new RedshiftService.TaggedResource(copyArn, "snapshot", "env", "test")), tags);
    }

    private long dumpFilesUnderCopies() {
        Path root = directory.resolve("redshift-dumps").resolve(ACCOUNT).resolve("copies");
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private Fixture fixture() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("redshift")).thenReturn("persistent");
        StorageFactory factory = new StorageFactory(config, access);
        RedshiftContainerManager manager = mock(RedshiftContainerManager.class);
        try {
            doAnswer(invocation -> {
                Files.createDirectories(invocation.getArgument(3, Path.class).getParent());
                Files.writeString(invocation.getArgument(3, Path.class), "backup contents");
                return null;
            }).when(manager).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return new Fixture(factory, config, manager,
                factory.create("redshift", "redshift-clusters.json", new TypeReference<Map<String, Cluster>>() { }),
                factory.create("redshift", "redshift-snapshots.json", new TypeReference<Map<String, Snapshot>>() { }),
                factory.create("redshift", "redshift-snapshot-copies.json", new TypeReference<Map<String, Snapshot>>() { }));
    }

    private static Cluster seedCluster(Fixture fixture, String id, Instant copyEnabledAt) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(id);
        cluster.setNodeType("dc2.large");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123!");
        cluster.setClusterStatus("available");
        cluster.setSnapshotRegion(SOURCE);
        cluster.setSnapshotCopyDestinationRegion(DESTINATION);
        cluster.setSnapshotCopyRetentionPeriod(7);
        cluster.setSnapshotCopyManualRetentionPeriod(-1);
        cluster.setSnapshotCopyEnabledAt(copyEnabledAt);
        fixture.clusters().putForAccount(ACCOUNT, id, cluster);
        return cluster;
    }

    private record Fixture(StorageFactory factory, EmulatorConfig config, RedshiftContainerManager manager,
                           AccountAwareStorageBackend<Cluster> clusters,
                           AccountAwareStorageBackend<Snapshot> snapshots,
                           AccountAwareStorageBackend<Snapshot> copies) {
        /** A service resolving every request to the given Region, over the same storage. */
        RedshiftService service(String region) {
            return new RedshiftService(factory, manager, config, new RegionResolver(region, ACCOUNT),
                    mock(RedshiftProxyManager.class), mock(DockerHostResolver.class), new RedshiftCredentialBroker());
        }
    }
}

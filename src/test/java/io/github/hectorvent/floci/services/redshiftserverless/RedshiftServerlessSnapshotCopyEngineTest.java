package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessSnapshotCopyService.CopyRule;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class RedshiftServerlessSnapshotCopyEngineTest {
    private static final String SOURCE = "us-east-1";
    private static final String DESTINATION = "us-west-2";
    private static final String ACCOUNT_ID = "123456789012";
    private static final String OTHER_ACCOUNT_ID = "999999999999";
    private static final String NAMESPACE = "copy-ns";
    private static final Instant CONFIGURED = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TAKEN = Instant.parse("2026-10-02T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

    @TempDir
    Path persistentPath;

    private static final String NAMESPACE_ID = "namespace-id";

    private AccountAwareStorageBackend<RedshiftServerlessSnapshot> snapshots;
    private AccountAwareStorageBackend<Namespace> namespaces;
    private RedshiftServerlessService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        for (String file : List.of("namespaces", "workgroups", "snapshots", "snapshotcopyconfigurations")) {
            AccountAwareStorageBackend<?> store = file.equals("snapshots")
                    ? spy(AccountAwareStorageBackend.inMemory(ACCOUNT_ID))
                    : AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
            if (file.equals("snapshots")) {
                snapshots = (AccountAwareStorageBackend<RedshiftServerlessSnapshot>) store;
            }
            if (file.equals("namespaces")) {
                namespaces = (AccountAwareStorageBackend<Namespace>) store;
            }
            when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-" + file + ".json"),
                    any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) store);
        }
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        RedshiftServerlessEndpoints endpoints = mock(RedshiftServerlessEndpoints.class);
        when(endpoints.allocate()).thenReturn(new Endpoint("localhost", 7100));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(persistentPath.toString());
        service = new RedshiftServerlessService(storageFactory, regionResolver, endpoints,
                mock(RedshiftServerlessRuntime.class), config);
        for (String account : List.of(ACCOUNT_ID, OTHER_ACCOUNT_ID)) {
            Namespace namespace = new Namespace();
            namespace.setNamespaceName(NAMESPACE);
            namespace.setNamespaceId(NAMESPACE_ID);
            namespaces.putForAccount(account, SOURCE + "::" + NAMESPACE, namespace);
        }
    }

    @Test
    void skipsARuleWhoseNamespaceWasRecreatedSinceTheRuleWasListed() throws IOException {
        seed(ACCOUNT_ID, SOURCE, "nightly", TAKEN, null);
        CopyRule stale = new CopyRule(ACCOUNT_ID, SOURCE, NAMESPACE, "the-earlier-namespace-id", DESTINATION,
                null, null, CONFIGURED);

        service.copySnapshots(NOW, List.of(stale));

        assertTrue(snapshots.get(DESTINATION + "::nightly").isEmpty());
        assertTrue(stored(ACCOUNT_ID, SOURCE, "nightly").getCopiedToRegions().isEmpty());
    }

    @Test
    void copiesMetadataAndDumpIntoTheDestinationRegion() throws IOException {
        RedshiftServerlessSnapshot source = seed(ACCOUNT_ID, SOURCE, "nightly", TAKEN, "-- data");

        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, null)));

        RedshiftServerlessSnapshot copy = stored(ACCOUNT_ID, DESTINATION, "nightly");
        assertEquals("nightly", copy.getSnapshotName());
        assertEquals(DESTINATION, copy.getRegion());
        assertEquals("AVAILABLE", copy.getStatus());
        assertEquals("arn:aws:redshift-serverless:" + DESTINATION + ":" + ACCOUNT_ID + ":snapshot/nightly",
                copy.getSnapshotArn());
        assertEquals(source.getSnapshotArn(), copy.getCopiedFromSnapshotArn());
        assertEquals(NAMESPACE, copy.getNamespaceName());
        assertEquals(source.getOwnerAccount(), copy.getOwnerAccount());
        assertEquals(source.getAdminUsername(), copy.getAdminUsername());
        assertEquals(Map.of("team", "data"), copy.getTags());
        assertEquals(NOW, copy.getSnapshotCreateTime());
        assertEquals(dumpOf(ACCOUNT_ID, DESTINATION, "nightly").getParent(),
                Path.of(copy.getSqlDump()).getParent());
        assertEquals("-- data", Files.readString(copiedDump(ACCOUNT_ID, "nightly")));
        assertTrue(Files.exists(dumpOf(ACCOUNT_ID, SOURCE, "nightly")), "the source dump stays");
        assertEquals(Set.of(DESTINATION), stored(ACCOUNT_ID, SOURCE, "nightly").getCopiedToRegions());
    }

    @Test
    void clearRemovesDumpsEvenWhenTheStoreWasEmptiedFirst() throws IOException {
        seed(ACCOUNT_ID, SOURCE, "nightly", TAKEN, "-- data");
        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, null)));
        Path copiedFile = copiedDump(ACCOUNT_ID, "nightly");
        assertTrue(Files.exists(copiedFile));

        // ResetAll empties every storage backend before it calls each service's clear().
        snapshots.clear();
        service.clear();

        assertFalse(Files.exists(dumpOf(ACCOUNT_ID, SOURCE, "nightly")));
        assertFalse(Files.exists(copiedFile));
    }

    @Test
    void copiesAMetadataOnlySnapshotWithoutADump() throws IOException {
        seed(ACCOUNT_ID, SOURCE, "bare", TAKEN, null);

        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, null)));

        assertNull(stored(ACCOUNT_ID, DESTINATION, "bare").getSqlDump());
    }

    @Test
    void appliesRetentionAndKmsKeyOfTheConfiguration() throws IOException {
        seed(ACCOUNT_ID, SOURCE, "kept", TAKEN, null);

        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, "key-in-west", 14)));

        RedshiftServerlessSnapshot kept = stored(ACCOUNT_ID, DESTINATION, "kept");
        assertEquals(14, kept.getRetentionPeriod());
        assertEquals("key-in-west", kept.getKmsKeyId());
    }

    @Test
    void keepsTheCopyIndefinitelyWhenTheConfigurationSaysSo() throws IOException {
        seed(ACCOUNT_ID, SOURCE, "forever", TAKEN, null);

        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, -1)));

        assertNull(stored(ACCOUNT_ID, DESTINATION, "forever").getRetentionPeriod());
        assertNull(stored(ACCOUNT_ID, DESTINATION, "forever").getKmsKeyId());
    }

    @Test
    void skipsSnapshotsCreatedBeforeTheConfiguration() throws IOException {
        seed(ACCOUNT_ID, SOURCE, "older", CONFIGURED.minus(1, ChronoUnit.DAYS), null);
        seed(ACCOUNT_ID, SOURCE, "newer", CONFIGURED.plus(1, ChronoUnit.DAYS), null);

        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, null)));

        assertTrue(snapshots.get(DESTINATION + "::older").isEmpty());
        assertTrue(snapshots.get(DESTINATION + "::newer").isPresent());
    }

    @Test
    void skipsSnapshotsThatAreNotAvailableOrBelongToAnotherNamespace() throws IOException {
        RedshiftServerlessSnapshot deleted = seed(ACCOUNT_ID, SOURCE, "gone", TAKEN, null);
        deleted.setStatus("DELETED");
        snapshots.put(SOURCE + "::gone", deleted);
        RedshiftServerlessSnapshot foreign = seed(ACCOUNT_ID, SOURCE, "elsewhere", TAKEN, null);
        foreign.setNamespaceName("another-ns");
        snapshots.put(SOURCE + "::elsewhere", foreign);

        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, null)));

        assertTrue(snapshots.get(DESTINATION + "::gone").isEmpty());
        assertTrue(snapshots.get(DESTINATION + "::elsewhere").isEmpty());
    }

    @Test
    void doesNotCopyAgainAfterTheCopyWasDeleted() throws IOException {
        seed(ACCOUNT_ID, SOURCE, "nightly", TAKEN, "-- data");
        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, null)));
        Path copiedFile = copiedDump(ACCOUNT_ID, "nightly");

        service.deleteSnapshot("nightly", DESTINATION);
        service.copySnapshots(NOW.plusSeconds(60), List.of(rule(ACCOUNT_ID, null, null)));

        assertTrue(snapshots.get(DESTINATION + "::nightly").isEmpty());
        assertFalse(Files.exists(copiedFile));
    }

    @Test
    void doesNotCopyAgainAfterTheCopyExpired() throws IOException {
        RedshiftServerlessSnapshot source = seed(ACCOUNT_ID, SOURCE, "nightly", TAKEN, null);
        // The source must outlive the copy, or the later sweep has nothing to copy and proves nothing.
        source.setRetentionPeriod(null);
        snapshots.put(SOURCE + "::nightly", source);
        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, 1)));
        assertTrue(snapshots.get(DESTINATION + "::nightly").isPresent());

        service.expireSnapshots(NOW.plus(2, ChronoUnit.DAYS), entry -> { });
        assertTrue(snapshots.get(DESTINATION + "::nightly").isEmpty());
        assertEquals(Set.of(DESTINATION), stored(ACCOUNT_ID, SOURCE, "nightly").getCopiedToRegions());
        service.copySnapshots(NOW.plus(2, ChronoUnit.DAYS), List.of(rule(ACCOUNT_ID, null, 1)));

        assertTrue(snapshots.get(DESTINATION + "::nightly").isEmpty());
    }

    @Test
    void replacesTheCopyOfAnEarlierSnapshotThatUsedTheSameName() throws IOException {
        seed(ACCOUNT_ID, SOURCE, "nightly", TAKEN, "-- old");
        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, null)));
        Path oldCopyFile = copiedDump(ACCOUNT_ID, "nightly");
        assertEquals("-- old", Files.readString(oldCopyFile));

        // Deleting a snapshot and taking another of the same name yields the same ARN.
        Instant retaken = TAKEN.plus(1, ChronoUnit.DAYS);
        seed(ACCOUNT_ID, SOURCE, "nightly", retaken, "-- new");
        service.copySnapshots(NOW.plusSeconds(60), List.of(rule(ACCOUNT_ID, null, null)));

        RedshiftServerlessSnapshot copy = stored(ACCOUNT_ID, DESTINATION, "nightly");
        assertEquals("-- new", Files.readString(copiedDump(ACCOUNT_ID, "nightly")));
        assertFalse(Files.exists(oldCopyFile), "the replaced copy's dump is removed once the new one is saved");
        assertEquals(retaken, copy.getCopiedFromSnapshotCreateTime());
        assertEquals(NOW.plusSeconds(60), copy.getSnapshotCreateTime());
        assertEquals(Set.of(DESTINATION), stored(ACCOUNT_ID, SOURCE, "nightly").getCopiedToRegions());
    }

    @Test
    void copiesASnapshotWhoseNameIsAsLongAsAllowed() throws IOException {
        String name = "a".repeat(220);
        seed(ACCOUNT_ID, SOURCE, name, TAKEN, "-- long");

        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, null)));

        assertEquals("-- long", Files.readString(Path.of(stored(ACCOUNT_ID, DESTINATION, name).getSqlDump())));
        assertTrue(Path.of(stored(ACCOUNT_ID, DESTINATION, name).getSqlDump()).getFileName().toString()
                .length() < 100);
    }

    @Test
    void keepsTheEarlierCopyUsableWhenSavingTheReplacementFails() throws IOException {
        seed(ACCOUNT_ID, SOURCE, "nightly", TAKEN, "-- old");
        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, null)));
        Path oldCopyFile = copiedDump(ACCOUNT_ID, "nightly");
        seed(ACCOUNT_ID, SOURCE, "nightly", TAKEN.plus(1, ChronoUnit.DAYS), "-- new");

        doThrow(new IllegalStateException("disk full")).when(snapshots)
                .putAllAtomicallyForAccount(eq(ACCOUNT_ID), any());
        service.copySnapshots(NOW.plusSeconds(60), List.of(rule(ACCOUNT_ID, null, null)));
        doCallRealMethod().when(snapshots).putAllAtomicallyForAccount(eq(ACCOUNT_ID), any());

        assertEquals("-- old", Files.readString(oldCopyFile));
        assertEquals(oldCopyFile, copiedDump(ACCOUNT_ID, "nightly"));
        try (Stream<Path> files = Files.list(oldCopyFile.getParent())) {
            assertEquals(1, files.count(), "the failed replacement leaves no dump behind");
        }
    }

    @Test
    void leavesTheSourceIntactAndRetriesWhenTheDumpIsMissing() throws IOException {
        RedshiftServerlessSnapshot source = seed(ACCOUNT_ID, SOURCE, "nightly", TAKEN, "-- data");
        Files.delete(dumpOf(ACCOUNT_ID, SOURCE, "nightly"));

        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, null)));

        assertTrue(snapshots.get(DESTINATION + "::nightly").isEmpty());
        assertTrue(stored(ACCOUNT_ID, SOURCE, "nightly").getCopiedToRegions().isEmpty());
        assertEquals("AVAILABLE", stored(ACCOUNT_ID, SOURCE, "nightly").getStatus());
        assertEquals(source.getSqlDump(), stored(ACCOUNT_ID, SOURCE, "nightly").getSqlDump());

        Files.writeString(dumpOf(ACCOUNT_ID, SOURCE, "nightly"), "-- data");
        service.copySnapshots(NOW.plusSeconds(60), List.of(rule(ACCOUNT_ID, null, null)));

        assertTrue(snapshots.get(DESTINATION + "::nightly").isPresent());
    }

    @Test
    void refusesADumpOutsideTheSourceDirectoryAndLeavesNoCopy() throws IOException {
        RedshiftServerlessSnapshot source = seed(ACCOUNT_ID, SOURCE, "nightly", TAKEN, null);
        Path outside = persistentPath.resolve("outside.sql");
        Files.writeString(outside, "-- secret");
        source.setSqlDump(outside.toString());
        snapshots.put(SOURCE + "::nightly", source);

        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, null)));

        assertTrue(snapshots.get(DESTINATION + "::nightly").isEmpty());
        assertTrue(stored(ACCOUNT_ID, SOURCE, "nightly").getCopiedToRegions().isEmpty());
    }

    @Test
    void repairsTheMarkerWithoutADuplicateAfterACrash() throws IOException {
        RedshiftServerlessSnapshot source = seed(ACCOUNT_ID, SOURCE, "nightly", TAKEN, null);
        RedshiftServerlessSnapshot committed = new RedshiftServerlessSnapshot(source);
        committed.setRegion(DESTINATION);
        committed.setSnapshotArn("arn:aws:redshift-serverless:" + DESTINATION + ":" + ACCOUNT_ID + ":snapshot/nightly");
        committed.setCopiedFromSnapshotArn(source.getSnapshotArn());
        committed.setCopiedFromSnapshotCreateTime(source.getSnapshotCreateTime());
        committed.setSnapshotCreateTime(NOW.minusSeconds(30));
        snapshots.put(DESTINATION + "::nightly", committed);

        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, null)));

        assertEquals(NOW.minusSeconds(30), stored(ACCOUNT_ID, DESTINATION, "nightly").getSnapshotCreateTime());
        assertEquals(Set.of(DESTINATION), stored(ACCOUNT_ID, SOURCE, "nightly").getCopiedToRegions());
        assertEquals(2, snapshots.scan(key -> true).size());
    }

    @Test
    void keepsAnExistingSnapshotOfTheSameNameInTheDestination() throws IOException {
        seed(ACCOUNT_ID, SOURCE, "nightly", TAKEN, null);
        RedshiftServerlessSnapshot users = seed(ACCOUNT_ID, DESTINATION, "nightly", TAKEN, null);
        users.setNamespaceName("users-own-ns");

        snapshots.put(DESTINATION + "::nightly", users);

        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, null)));

        RedshiftServerlessSnapshot kept = stored(ACCOUNT_ID, DESTINATION, "nightly");
        assertEquals("users-own-ns", kept.getNamespaceName());
        assertNull(kept.getCopiedFromSnapshotArn());
        assertEquals(Set.of(DESTINATION), stored(ACCOUNT_ID, SOURCE, "nightly").getCopiedToRegions());
    }

    @Test
    void copiesPerAccountWithoutCollision() throws IOException {
        seed(ACCOUNT_ID, SOURCE, "nightly", TAKEN, "-- first");
        seed(OTHER_ACCOUNT_ID, SOURCE, "nightly", TAKEN, "-- second");

        service.copySnapshots(NOW, List.of(rule(ACCOUNT_ID, null, null), rule(OTHER_ACCOUNT_ID, null, null)));

        RedshiftServerlessSnapshot first = stored(ACCOUNT_ID, DESTINATION, "nightly");
        RedshiftServerlessSnapshot second = stored(OTHER_ACCOUNT_ID, DESTINATION, "nightly");
        assertNotEquals(first.getSnapshotArn(), second.getSnapshotArn());
        assertTrue(second.getSnapshotArn().contains(":" + OTHER_ACCOUNT_ID + ":"));
        assertEquals("-- first", Files.readString(copiedDump(ACCOUNT_ID, "nightly")));
        assertEquals("-- second", Files.readString(copiedDump(OTHER_ACCOUNT_ID, "nightly")));
    }

    private static CopyRule rule(String accountId, String kmsKeyId, Integer retention) {
        return new CopyRule(accountId, SOURCE, NAMESPACE, NAMESPACE_ID, DESTINATION, kmsKeyId, retention, CONFIGURED);
    }

    private RedshiftServerlessSnapshot stored(String accountId, String region, String name) {
        return snapshots.getForAccount(accountId, region + "::" + name).orElseThrow();
    }

    private Path copiedDump(String accountId, String name) {
        return Path.of(stored(accountId, DESTINATION, name).getSqlDump());
    }

    private Path dumpOf(String accountId, String region, String name) {
        return persistentPath.resolve("redshift-serverless-dumps").resolve(accountId).resolve(region)
                .resolve(name + ".sql").toAbsolutePath().normalize();
    }

    private RedshiftServerlessSnapshot seed(String accountId, String region, String name, Instant taken,
                                            String dump) throws IOException {
        RedshiftServerlessSnapshot snapshot = new RedshiftServerlessSnapshot();
        snapshot.setSnapshotName(name);
        snapshot.setNamespaceName(NAMESPACE);
        snapshot.setNamespaceArn("arn:aws:redshift-serverless:" + region + ":" + accountId + ":namespace/n");
        snapshot.setRegion(region);
        snapshot.setAccountId(accountId);
        snapshot.setOwnerAccount(accountId);
        snapshot.setSnapshotArn("arn:aws:redshift-serverless:" + region + ":" + accountId + ":snapshot/" + name);
        snapshot.setStatus("AVAILABLE");
        snapshot.setSnapshotCreateTime(taken);
        snapshot.setAdminUsername("admin");
        snapshot.setAdminUserPassword("Secret123");
        snapshot.setKmsKeyId("key-in-" + region);
        snapshot.setRetentionPeriod(30);
        snapshot.setTags(Map.of("team", "data"));
        if (dump != null) {
            Path file = dumpOf(accountId, region, name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, dump);
            snapshot.setSqlDump(file.toString());
        }
        snapshots.putForAccount(accountId, region + "::" + name, snapshot);
        return snapshot;
    }
}

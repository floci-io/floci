package io.floci.aws.services.docdb;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.docdb.container.DocDbContainerHandle;
import io.floci.aws.services.docdb.container.DocDbContainerManager;
import io.floci.aws.services.docdb.model.DocDbCluster;
import io.floci.aws.services.docdb.model.DocDbClusterSettings;
import io.floci.aws.services.docdb.model.DocDbClusterSnapshot;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.kms.model.KmsKey;
import io.floci.aws.services.rds.RdsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Snapshots and restores with a container behind each cluster, the container layer mocked: what a
 * snapshot reads from its cluster is what a restore loads into the new one, and the failure paths
 * leave nothing behind.
 */
class DocDbClusterSnapshotServiceTest {

    private static final String KEY_ARN = "arn:aws:kms:us-east-1:000000000000:key/k1";
    private static final String ARCHIVE = "H4sIAAAAAAAA/2NgYGBgBGIQAAAA//8DAP2uCU4MAAAA";

    private DocDbService docDbService;
    private DocDbContainerManager containerManager;
    private EmulatorConfig.DocDbServiceConfig docdbConfig;
    private StorageBackend<String, String> archives;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> {
            StorageBackend<String, Object> store = AccountAwareStorageBackend.inMemory("000000000000");
            if ("docdb-cluster-snapshot-data.json".equals(inv.getArgument(1))) {
                archives = cast(store);
            }
            return store;
        });
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        docdbConfig = mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.docdb()).thenReturn(docdbConfig);
        when(docdbConfig.mock()).thenReturn(false);
        when(docdbConfig.defaultImage()).thenReturn("mongo:7.0");
        containerManager = mock(DocDbContainerManager.class);
        // Every cluster gets a container named after it.
        when(containerManager.tryStart(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new DocDbContainerHandle("container-" + inv.getArgument(0),
                        inv.getArgument(0), "localhost", 27017));
        when(containerManager.dumpCluster("container-source")).thenReturn(ARCHIVE);
        when(containerManager.masterPassword("container-source")).thenReturn("source-password");
        KmsService kmsService = mock(KmsService.class);
        KmsKey key = new KmsKey();
        key.setKeyId("k1");
        key.setArn(KEY_ARN);
        key.setEnabled(true);
        key.setKeyState("Enabled");
        doReturn(key).when(kmsService).describeKey(KEY_ARN, "us-east-1");
        docDbService = new DocDbService(config, new RegionResolver("us-east-1", "000000000000"), containerManager,
                storageFactory, mock(RdsService.class), mock(Ec2Service.class), kmsService);
    }

    @Test
    void theSnapshotsArchiveReachesTheRestoredClusterWithTheSourcesPassword() {
        source(DocDbClusterSettings.defaults());
        docDbService.createDbClusterSnapshot("snap", "source", Map.of());

        docDbService.restoreDbClusterFromSnapshot("restored", "snap", null, DocDbClusterSettings.defaults(), Map.of());

        verify(containerManager).tryStart(eq("restored"), anyString(), eq("admin"), eq("source-password"), anyString());
        verify(containerManager).restoreCluster("container-restored", ARCHIVE);
    }

    @Test
    void aPointInTimeRestoreLoadsTheSourcesCurrentData() {
        source(DocDbClusterSettings.defaults());

        docDbService.restoreDbClusterToPointInTime("copy", "source", DocDbClusterSettings.defaults(), Map.of());

        verify(containerManager).dumpCluster("container-source");
        verify(containerManager).tryStart(eq("copy"), anyString(), eq("admin"), eq("source-password"), anyString());
        verify(containerManager).restoreCluster("container-copy", ARCHIVE);
    }

    @Test
    void aPointInTimeRestoreOfAnUnreadableSourceFailsLikeASnapshotAndCreatesNothing() {
        source(DocDbClusterSettings.defaults());
        when(containerManager.dumpCluster("container-source")).thenThrow(new IllegalStateException("mongodump exited 1"));

        AwsException e = assertThrows(AwsException.class, () -> docDbService.restoreDbClusterToPointInTime(
                "copy", "source", DocDbClusterSettings.defaults(), Map.of()));

        assertEquals("InvalidDBClusterStateFault", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertThrows(AwsException.class, () -> docDbService.getDbCluster("copy"));
        verify(containerManager, never()).tryStart(eq("copy"), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aFailedLoadRemovesTheRestoredClusterEvenUnderDeletionProtection() {
        source(DocDbClusterSettings.defaults());
        docDbService.createDbClusterSnapshot("snap", "source", Map.of());
        doThrow(new IllegalStateException("mongorestore exited 1"))
                .when(containerManager).restoreCluster("container-restored", ARCHIVE);
        DocDbClusterSettings protectedCluster = new DocDbClusterSettings(null, null, null, null, null, null,
                null, null, true);

        AwsException e = assertThrows(AwsException.class, () -> docDbService.restoreDbClusterFromSnapshot(
                "restored", "snap", null, protectedCluster, Map.of()));

        assertEquals("InvalidRestoreFault", e.getErrorCode());
        AwsException gone = assertThrows(AwsException.class, () -> docDbService.getDbCluster("restored"));
        assertEquals("DBClusterNotFoundFault", gone.getErrorCode());
        verify(containerManager).stop(argThat(handle -> "container-restored".equals(handle.getContainerId())));
    }

    @Test
    void bothRestoresKeepTheSourcesEncryptionAndKey() {
        source(new DocDbClusterSettings(null, null, null, true, KEY_ARN, null, null, null, null));
        docDbService.createDbClusterSnapshot("snap", "source", Map.of());

        DocDbCluster fromSnapshot = docDbService.restoreDbClusterFromSnapshot("restored", "snap", null,
                DocDbClusterSettings.defaults(), Map.of());
        DocDbCluster pointInTime = docDbService.restoreDbClusterToPointInTime("copy", "source",
                DocDbClusterSettings.defaults(), Map.of());

        for (DocDbCluster cluster : List.of(fromSnapshot, pointInTime)) {
            assertTrue(cluster.isStorageEncrypted(), cluster.getDbClusterIdentifier());
            assertEquals(KEY_ARN, cluster.getKmsKeyId(), cluster.getDbClusterIdentifier());
        }
    }

    @Test
    void anUnencryptedSnapshotRestoresUnencryptedOrEncryptedUnderANamedKey() {
        source(DocDbClusterSettings.defaults());
        docDbService.createDbClusterSnapshot("snap", "source", Map.of());

        DocDbCluster plain = docDbService.restoreDbClusterFromSnapshot("restored", "snap", null,
                DocDbClusterSettings.defaults(), Map.of());
        assertFalse(plain.isStorageEncrypted());
        assertNull(plain.getKmsKeyId());

        DocDbCluster keyed = docDbService.restoreDbClusterFromSnapshot("keyed", "snap", null,
                new DocDbClusterSettings(null, null, null, null, KEY_ARN, null, null, null, null), Map.of());
        assertTrue(keyed.isStorageEncrypted());
        assertEquals(KEY_ARN, keyed.getKmsKeyId());
    }

    @Test
    void aPointInTimeRestoreRefusesAKeyForAnUnencryptedSource() {
        source(DocDbClusterSettings.defaults());

        AwsException e = assertThrows(AwsException.class, () -> docDbService.restoreDbClusterToPointInTime(
                "keyed", "source",
                new DocDbClusterSettings(null, null, null, null, KEY_ARN, null, null, null, null), Map.of()));
        assertEquals("InvalidParameterCombination", e.getErrorCode());
    }

    @Test
    void aSnapshotWhoseArchiveIsLostIsRefusedBeforeAnyClusterExists() {
        source(DocDbClusterSettings.defaults());
        docDbService.createDbClusterSnapshot("snap", "source", Map.of());
        archives.clear();

        AwsException e = assertThrows(AwsException.class, () -> docDbService.restoreDbClusterFromSnapshot(
                "restored", "snap", null, DocDbClusterSettings.defaults(), Map.of()));

        assertEquals("InvalidDBClusterSnapshotStateFault", e.getErrorCode());
        assertThrows(AwsException.class, () -> docDbService.getDbCluster("restored"));
        verify(containerManager, never()).tryStart(eq("restored"), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aMockModeSnapshotIsMetadataOnlyAndRestoresOnlyInMockMode() {
        when(docdbConfig.mock()).thenReturn(true);
        source(DocDbClusterSettings.defaults());
        DocDbClusterSnapshot snapshot = docDbService.createDbClusterSnapshot("snap", "source", Map.of());
        assertTrue(snapshot.isMetadataOnly());
        docDbService.restoreDbClusterFromSnapshot("in-mock", "snap", null, DocDbClusterSettings.defaults(), Map.of());

        when(docdbConfig.mock()).thenReturn(false);
        AwsException e = assertThrows(AwsException.class, () -> docDbService.restoreDbClusterFromSnapshot(
                "outside-mock", "snap", null, DocDbClusterSettings.defaults(), Map.of()));
        assertEquals("InvalidDBClusterSnapshotStateFault", e.getErrorCode());
        verify(containerManager, never()).restoreCluster(anyString(), anyString());
    }

    @Test
    void aSnapshotIsNamedByIdentifierOrByItsOwnArnOnly() {
        source(DocDbClusterSettings.defaults());
        DocDbClusterSnapshot snapshot = docDbService.createDbClusterSnapshot("snap", "source", Map.of());
        String arn = snapshot.getDbClusterSnapshotArn();

        assertEquals("snap", docDbService.getDbClusterSnapshot(arn).getDbClusterSnapshotIdentifier());
        assertTrue(docDbService.hasClusterSnapshot(arn, "us-east-1"));
        String otherRegion = arn.replace(":us-east-1:", ":eu-west-1:");
        assertFalse(docDbService.hasClusterSnapshot(otherRegion, "us-east-1"));
        assertEquals("DBClusterSnapshotNotFoundFault",
                assertThrows(AwsException.class, () -> docDbService.getDbClusterSnapshot(otherRegion)).getErrorCode());
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> docDbService.getDbClusterSnapshot("arn:aws:rds:us-east-1:000000000000:cluster:source"))
                .getErrorCode());
    }

    @Test
    void anIdentifierIsNarrowedByTheClusterAndTypeFilters() {
        source(DocDbClusterSettings.defaults());
        docDbService.createDbClusterSnapshot("snap", "source", Map.of());

        assertEquals(1, docDbService.describeDbClusterSnapshots("snap", "source", "manual").size());
        assertTrue(docDbService.describeDbClusterSnapshots("snap", "another-cluster", null).isEmpty());
        assertTrue(docDbService.describeDbClusterSnapshots("snap", null, "automated").isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static StorageBackend<String, String> cast(StorageBackend<String, Object> store) {
        return (StorageBackend<String, String>) (StorageBackend<?, ?>) store;
    }

    private void source(DocDbClusterSettings settings) {
        docDbService.createDbCluster("source", "5.0.0", "admin", "source-password", false, settings, Map.of());
    }
}

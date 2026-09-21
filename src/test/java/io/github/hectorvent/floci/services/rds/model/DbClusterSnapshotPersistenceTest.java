package io.github.hectorvent.floci.services.rds.model;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbClusterSnapshotPersistenceTest {

    @Test
    void clusterSnapshotMetadataPersistsAcrossStorageInstances(@TempDir Path directory) {
        Path file = directory.resolve("rds-cluster-snapshots.json");
        DbClusterSnapshot snapshot = new DbClusterSnapshot();
        snapshot.setDbClusterSnapshotIdentifier("cluster-snapshot");
        snapshot.setDbClusterSnapshotArn(
                "arn:aws:rds:us-east-1:123456789012:cluster-snapshot:cluster-snapshot");
        snapshot.setSourceDbClusterSnapshotArn(
                "arn:aws:rds:us-east-1:123456789012:cluster-snapshot:source-snapshot");
        snapshot.setDbClusterIdentifier("source-cluster");
        snapshot.setSnapshotCreateTime(Instant.parse("2026-09-21T00:00:00Z"));
        snapshot.setClusterCreateTime(Instant.parse("2026-09-20T00:00:00Z"));
        snapshot.setEngine(DatabaseEngine.POSTGRES);
        snapshot.setEngineIdentifier("aurora-postgresql");
        snapshot.setEngineVersion("16.3");
        snapshot.setStatus("available");
        snapshot.setPercentProgress(100);
        snapshot.setSnapshotType("manual");
        snapshot.setMasterUsername("admin");
        snapshot.setMasterPassword("password");
        snapshot.setDatabaseName("appdb");
        snapshot.setPort(5432);
        snapshot.setIamDatabaseAuthenticationEnabled(true);
        snapshot.setStorageEncrypted(true);
        snapshot.setEngineMode("provisioned");
        snapshot.setDbClusterResourceId("cluster-RESOURCE");
        snapshot.setDbClusterParameterGroupName("default.aurora-postgresql16");
        snapshot.setDbSubnetGroupName("default");
        snapshot.setVpcId("vpc-default");
        snapshot.setAvailabilityZone("us-east-1a");
        snapshot.setTags(Map.of("owner", "platform"));
        snapshot.setRestoreAccountIds(List.of("111111111111", "all"));

        PersistentStorage<String, DbClusterSnapshot> writer = new PersistentStorage<>(
                file, new TypeReference<Map<String, DbClusterSnapshot>>() {});
        writer.put(snapshot.getDbClusterSnapshotIdentifier(), snapshot);

        PersistentStorage<String, DbClusterSnapshot> reader = new PersistentStorage<>(
                file, new TypeReference<Map<String, DbClusterSnapshot>>() {});
        reader.load();

        Optional<DbClusterSnapshot> restored = reader.get(
                snapshot.getDbClusterSnapshotIdentifier());
        assertTrue(restored.isPresent());
        assertEquals(snapshot.getDbClusterSnapshotArn(), restored.get().getDbClusterSnapshotArn());
        assertEquals(snapshot.getSourceDbClusterSnapshotArn(),
                restored.get().getSourceDbClusterSnapshotArn());
        assertEquals(DatabaseEngine.POSTGRES, restored.get().getEngine());
        assertEquals("aurora-postgresql", restored.get().getEngineIdentifier());
        assertEquals("password", restored.get().getMasterPassword());
        assertTrue(restored.get().isIamDatabaseAuthenticationEnabled());
        assertTrue(restored.get().isStorageEncrypted());
        assertEquals(Map.of("owner", "platform"), restored.get().getTags());
        assertEquals(List.of("111111111111", "all"), restored.get().getRestoreAccountIds());
    }
}

package io.github.hectorvent.floci.services.rds.model;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbClusterPersistenceTest {

    @Test
    void serverlessV2ScalingConfigurationPersistsAcrossInstances(@TempDir Path directory) {
        Path file = directory.resolve("rds-clusters.json");
        DbCluster cluster = new DbCluster();
        cluster.setDbClusterIdentifier("serverless-cluster");
        cluster.setEngineIdentifier("aurora-postgresql");
        cluster.setServerlessV2MinCapacity(0.0);
        cluster.setServerlessV2MaxCapacity(16.0);
        cluster.setServerlessV2SecondsUntilAutoPause(600);
        cluster.setDeletionProtection(true);
        cluster.setVpcSecurityGroupIds(List.of("sg-123", "sg-456"));

        PersistentStorage<String, DbCluster> writer = new PersistentStorage<>(
                file, new TypeReference<Map<String, DbCluster>>() {});
        writer.put(cluster.getDbClusterIdentifier(), cluster);

        PersistentStorage<String, DbCluster> reader = new PersistentStorage<>(
                file, new TypeReference<Map<String, DbCluster>>() {});
        reader.load();

        Optional<DbCluster> restored = reader.get(cluster.getDbClusterIdentifier());
        assertTrue(restored.isPresent());
        assertEquals("aurora-postgresql", restored.get().getEngineIdentifier());
        assertEquals(0.0, restored.get().getServerlessV2MinCapacity());
        assertEquals(16.0, restored.get().getServerlessV2MaxCapacity());
        assertEquals(600, restored.get().getServerlessV2SecondsUntilAutoPause());
        assertTrue(restored.get().isDeletionProtection());
        assertEquals(List.of("sg-123", "sg-456"), restored.get().getVpcSecurityGroupIds());
    }
}

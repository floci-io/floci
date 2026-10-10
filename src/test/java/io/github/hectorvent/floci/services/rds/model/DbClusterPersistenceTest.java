package io.github.hectorvent.floci.services.rds.model;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    }

    @Test
    void associatedRolesPersistAcrossInstances(@TempDir Path directory) {
        DbCluster cluster = new DbCluster();
        cluster.setDbClusterIdentifier("roles-cluster");
        cluster.getAssociatedRoles().add(new DbRoleAssociation("arn:aws:iam::000000000000:role/a", "s3Import"));
        cluster.getAssociatedRoles().add(new DbRoleAssociation("arn:aws:iam::000000000000:role/b", null));
        DbInstance instance = new DbInstance();
        instance.setDbInstanceIdentifier("roles-instance");
        instance.getAssociatedRoles().add(new DbRoleAssociation("arn:aws:iam::000000000000:role/c", "Lambda"));

        Path clusters = directory.resolve("rds-clusters.json");
        new PersistentStorage<String, DbCluster>(clusters, new TypeReference<Map<String, DbCluster>>() {})
                .put(cluster.getDbClusterIdentifier(), cluster);
        Path instances = directory.resolve("rds-instances.json");
        new PersistentStorage<String, DbInstance>(instances, new TypeReference<Map<String, DbInstance>>() {})
                .put(instance.getDbInstanceIdentifier(), instance);

        PersistentStorage<String, DbCluster> clusterReader = new PersistentStorage<>(
                clusters, new TypeReference<Map<String, DbCluster>>() {});
        clusterReader.load();
        List<DbRoleAssociation> clusterRoles = clusterReader.get("roles-cluster").orElseThrow().getAssociatedRoles();
        assertEquals(2, clusterRoles.size());
        assertEquals("arn:aws:iam::000000000000:role/a", clusterRoles.get(0).getRoleArn());
        assertEquals("s3Import", clusterRoles.get(0).getFeatureName());
        assertEquals(DbRoleAssociation.ACTIVE, clusterRoles.get(0).getStatus());
        assertNull(clusterRoles.get(1).getFeatureName());

        PersistentStorage<String, DbInstance> instanceReader = new PersistentStorage<>(
                instances, new TypeReference<Map<String, DbInstance>>() {});
        instanceReader.load();
        List<DbRoleAssociation> instanceRoles = instanceReader.get("roles-instance").orElseThrow().getAssociatedRoles();
        assertEquals(1, instanceRoles.size());
        assertEquals("Lambda", instanceRoles.get(0).getFeatureName());
    }

    @Test
    void stateSavedBeforeRoleAssociationsLoadsWithNone(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("rds-clusters.json");
        Files.writeString(file, "{\"legacy-cluster\":{\"dbClusterIdentifier\":\"legacy-cluster\"}}");

        PersistentStorage<String, DbCluster> reader = new PersistentStorage<>(
                file, new TypeReference<Map<String, DbCluster>>() {});
        reader.load();

        assertTrue(reader.get("legacy-cluster").orElseThrow().getAssociatedRoles().isEmpty());
    }
}

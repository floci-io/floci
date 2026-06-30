package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forward-compatibility guard: a {@code memorydb-clusters.json} snapshot written by an
 * older build still carried the now-removed {@code authToken}/{@code authMode} fields.
 * {@link PersistentStorage} uses a plain ObjectMapper (which fails on unknown properties
 * by default), so without {@code @JsonIgnoreProperties(ignoreUnknown = true)} on
 * {@link Cluster} the load would throw and the entire cluster store would start empty.
 */
class MemoryDbPersistenceTest {

    @Test
    void loadsLegacySnapshotContainingRemovedAuthFields(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("memorydb-clusters.json");
        Files.writeString(file, """
                {
                  "legacy-cluster": {
                    "name": "legacy-cluster",
                    "status": "AVAILABLE",
                    "nodeType": "db.t4g.small",
                    "aclName": "open-access",
                    "authMode": "PASSWORD",
                    "authToken": "old-plaintext-token",
                    "arn": "arn:aws:memorydb:us-east-1:000000000000:cluster/legacy-cluster"
                  }
                }
                """);

        PersistentStorage<String, Cluster> storage =
                new PersistentStorage<>(file, new TypeReference<Map<String, Cluster>>() {});
        storage.load();

        Optional<Cluster> loaded = storage.get("legacy-cluster");
        assertTrue(loaded.isPresent(), "legacy cluster must survive the schema change");
        assertEquals("legacy-cluster", loaded.get().getName());
        assertEquals("open-access", loaded.get().getAclName());
    }
}

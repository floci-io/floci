package io.floci.aws.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegionScopedStorageTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void legacyKeysAndScopedKeysCannotEscapeTheirRegion(String mode) {
        StorageFactory factory = StorageFactories.open(directory, mode, "000000000000");
        try {
            StorageBackend<String, String> legacy = factory.create(
                    "appconfig", "records.json", new TypeReference<>() {});
            legacy.put("existing", "legacy");
            RegionScopedStorage<String> east = store(factory, "us-east-1");
            RegionScopedStorage<String> west = store(factory, "us-west-2");
            assertEquals(Optional.of("legacy"), east.get("existing"));
            assertEquals(Optional.empty(), west.get("existing"));
            east.put("existing", "east");
            west.put("existing", "west");
            legacy.put("untouched", "older-record");

            assertEquals(Optional.empty(), legacy.get("existing"));
            assertEquals(Optional.of("east"), east.get("existing"));
            assertEquals(Optional.of("west"), west.get("existing"));
            assertEquals(Set.of("existing", "untouched"), east.keys());
            assertEquals(Set.of("existing"), west.keys());
            assertEquals(List.of("west"), west.scan(key -> key.equals("existing")));
            List<String> selected = west.scan(key -> true);
            selected.clear();
            assertEquals(List.of("west"), west.scan(key -> true));

            assertEquals(Optional.empty(), east.get("us-west-2/existing"));
            east.delete("us-west-2/existing");
            assertThrows(IllegalArgumentException.class, () -> east.put("us-west-2/existing", "wrong"));
            assertEquals(Optional.of("west"), west.get("existing"));
            east.clear();
            assertEquals(Set.of(), east.keys());
            assertEquals(Optional.of("west"), west.get("existing"));
        } finally {
            factory.shutdownAll();
        }
    }

    /** A key may hold a slash, as a Glue name may; only a Region and a slash at its start are refused. */
    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent"})
    void aKeyWithASlashThatIsNotARegionStaysInItsRegion(String mode) {
        StorageFactory factory = StorageFactories.open(directory, mode, "000000000000");
        try {
            StorageBackend<String, String> legacy = factory.create(
                    "appconfig", "records.json", new TypeReference<>() {});
            legacy.put("older/record", "legacy");
            RegionScopedStorage<String> east = store(factory, "us-east-1");
            RegionScopedStorage<String> west = store(factory, "us-west-2");

            west.put("sales/2024:orders", "west");

            assertEquals(Optional.of("west"), west.get("sales/2024:orders"));
            assertEquals(Optional.empty(), east.get("sales/2024:orders"));
            assertEquals(Set.of("sales/2024:orders"), west.keys());
            assertEquals(Optional.of("legacy"), east.get("older/record"));
            assertEquals(Set.of("older/record"), east.keys());
            assertEquals(Optional.empty(), west.get("older/record"));
        } finally {
            factory.shutdownAll();
        }
    }

    /** Batch writes and atomic deletions act in the request's Region only, as single ones do. */
    @Test
    void batchesStayInTheirRegion() {
        StorageFactory factory = StorageFactories.open(directory, "wal", "000000000000");
        try {
            RegionScopedStorage<String> east = store(factory, "us-east-1");
            RegionScopedStorage<String> west = store(factory, "us-west-2");

            east.putAllAtomically(Map.of("a", "east-a", "b", "east-b"));
            west.putAll(Map.of("a", "west-a"));
            west.deleteAllAtomically(Set.of("a", "b"));

            assertEquals(Set.of("a", "b"), east.keys());
            assertEquals(Optional.of("east-a"), east.get("a"));
            assertEquals(Set.of(), west.keys());
        } finally {
            factory.shutdownAll();
        }
    }

    private static RegionScopedStorage<String> store(StorageFactory factory, String region) {
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getDefaultRegion()).thenReturn("us-east-1");
        when(regions.getRegion()).thenReturn(region);
        return new RegionScopedStorage<>(factory, regions, "appconfig", "records.json", new TypeReference<>() {});
    }

}

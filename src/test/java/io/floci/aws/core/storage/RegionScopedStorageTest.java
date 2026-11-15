package io.floci.aws.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

            // A key naming another Region is a key of this one: it never reaches that Region's record.
            assertEquals(Optional.empty(), east.get("us-west-2/existing"));
            east.delete("us-west-2/existing");
            east.put("us-west-2/existing", "east-named-west");
            assertEquals(Optional.of("east-named-west"), east.get("us-west-2/existing"));
            assertEquals(Set.of("existing", "untouched", "us-west-2/existing"), east.keys());
            assertEquals(Optional.of("west"), west.get("existing"));
            assertEquals(Set.of("existing"), west.keys());
            east.clear();
            assertEquals(Set.of(), east.keys());
            assertEquals(Optional.of("west"), west.get("existing"));
        } finally {
            factory.shutdownAll();
        }
    }

    /**
     * A key may hold a slash, as a Glue name may, a Region and a slash at its start included. A label shaped
     * like a Region that no partition admits ({@code my-cd-1}) is part of the key.
     */
    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void aKeyWithASlashThatIsNotARegionStaysInItsRegion(String mode) {
        StorageFactory factory = StorageFactories.open(directory, mode, "000000000000");
        try {
            StorageBackend<String, String> legacy = factory.create(
                    "appconfig", "records.json", new TypeReference<>() {});
            legacy.put("older/record", "legacy");
            RegionScopedStorage<String> east = store(factory, "us-east-1");
            RegionScopedStorage<String> west = store(factory, "us-west-2");

            west.put("sales/2024:orders", "west");
            west.put("my-cd-1/plan", "west-plan");

            assertEquals(Optional.of("west"), west.get("sales/2024:orders"));
            assertEquals(Optional.of("west-plan"), west.get("my-cd-1/plan"));
            assertEquals(Optional.empty(), east.get("sales/2024:orders"));
            assertEquals(Set.of("sales/2024:orders", "my-cd-1/plan"), west.keys());
            assertEquals(Optional.of("legacy"), east.get("older/record"));
            assertEquals(Set.of("older/record"), east.keys());
            assertEquals(Optional.empty(), west.get("older/record"));

            west.put("eu-west-1/plan", "west-named-eu");
            east.put("us-east-1/plan", "east-named-east");
            assertEquals(Optional.of("west-named-eu"), west.get("eu-west-1/plan"));
            assertEquals(Optional.of("east-named-east"), east.get("us-east-1/plan"));
            assertEquals(Optional.empty(), east.get("eu-west-1/plan"));
            assertEquals(Optional.empty(), west.get("us-east-1/plan"));
            assertEquals(Set.of("sales/2024:orders", "my-cd-1/plan", "eu-west-1/plan"), west.keys());
            assertEquals(Set.of("older/record", "us-east-1/plan"), east.keys());
            east.delete("us-east-1/plan");
            assertEquals(Optional.empty(), east.get("us-east-1/plan"));
            assertEquals(Optional.of("legacy"), east.get("older/record"));
        } finally {
            factory.shutdownAll();
        }
    }

    /**
     * A matching deletion acts in the request's Region only, in one commit; in the default Region it takes
     * the regionless keys too.
     */
    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void matchingDeletionsStayInTheirRegion(String mode) {
        StorageFactory factory = StorageFactories.open(directory, mode, "000000000000");
        try {
            StorageBackend<String, String> legacy = factory.create(
                    "appconfig", "records.json", new TypeReference<>() {});
            legacy.put("older", "db");
            RegionScopedStorage<String> east = store(factory, "us-east-1");
            RegionScopedStorage<String> west = store(factory, "us-west-2");
            east.put("a", "db");
            east.put("kept", "other");
            west.put("a", "db");

            StorageOperations.deleteMatchingAtomically(west, "db"::equals);

            assertEquals(Set.of(), west.keys());
            assertEquals(Set.of("a", "kept", "older"), east.keys());

            StorageOperations.deleteMatchingAtomically(east, "db"::equals);

            assertEquals(Set.of("kept"), east.keys());
            assertEquals(Set.of("us-east-1/kept"), legacy.keys());
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

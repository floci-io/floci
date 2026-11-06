package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppConfigStorageTest {
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
            AppConfigStorage<String> east = store(factory, "us-east-1");
            AppConfigStorage<String> west = store(factory, "us-west-2");
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

    private static AppConfigStorage<String> store(StorageFactory factory, String region) {
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getDefaultRegion()).thenReturn("us-east-1");
        when(regions.getRegion()).thenReturn(region);
        return new AppConfigStorage<>(factory, regions, "appconfig", "records.json", new TypeReference<>() {});
    }

}

package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.testing.StorageFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class Ec2ImageCreationProvenanceStorageReloadTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-west-2";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void creationProvenanceSurvivesReloadIndependentlyOfRuntimeAncestor(String mode) {
        List<Image> images = imagesWithCreationProvenance();
        StorageFactory first = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            putImages(first, images);
        } finally {
            first.shutdownAll();
        }

        StorageFactory second = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            assertImages(second, images);
        } finally {
            second.shutdownAll();
        }
    }

    @Test
    void creationProvenanceSurvivesUncompactedWalReplay() throws IOException {
        List<Image> images = imagesWithCreationProvenance();
        Path replayDirectory = Files.createDirectory(directory.resolve("replay"));
        StorageFactory first = StorageFactories.open(directory, "wal", ACCOUNT);
        try {
            putImages(first, images);
            // Keep the journal before shutdown compacts it, then replay it without a snapshot.
            Files.copy(directory.resolve("ec2-registered-images.wal"),
                    replayDirectory.resolve("ec2-registered-images.wal"));
        } finally {
            first.shutdownAll();
        }

        StorageFactory second = StorageFactories.open(replayDirectory, "wal", ACCOUNT);
        try {
            assertImages(second, images);
        } finally {
            second.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void legacyPersistedRuntimeAncestryDoesNotInventCreationProvenance(String mode) throws IOException {
        String fileName = "wal".equals(mode)
                ? "ec2-registered-images-snapshot.json" : "ec2-registered-images.json";
        Files.writeString(directory.resolve(fileName), """
                {
                  "%s/%s::ami-old": {
                    "imageId": "ami-old",
                    "region": "%s",
                    "sourceImageId": "ami-catalog",
                    "dockerImage": "example/captured:1"
                  }
                }
                """.formatted(ACCOUNT, REGION, REGION));

        StorageFactory first = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            StorageBackend<String, Image> store = openImages(first);
            Image legacy = store.get(REGION + "::ami-old").orElseThrow();
            assertLegacyImage(legacy);
            store.put(REGION + "::ami-old", legacy);
        } finally {
            first.shutdownAll();
        }

        StorageFactory second = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            assertLegacyImage(openImages(second).get(REGION + "::ami-old").orElseThrow());
        } finally {
            second.shutdownAll();
        }
    }

    private static StorageBackend<String, Image> openImages(StorageFactory factory) {
        return factory.create("ec2", "ec2-registered-images.json", new TypeReference<Map<String, Image>>() {});
    }

    private static void putImages(StorageFactory factory, List<Image> images) {
        StorageBackend<String, Image> store = openImages(factory);
        for (Image image : images) {
            store.put(REGION + "::" + image.getImageId(), image);
        }
    }

    private static void assertImages(StorageFactory factory, List<Image> images) {
        StorageBackend<String, Image> store = openImages(factory);
        for (Image expected : images) {
            Image restored = store.get(REGION + "::" + expected.getImageId()).orElseThrow();
            assertEquals(expected.getImageId(), restored.getImageId());
            assertEquals(expected.getRegion(), restored.getRegion());
            assertEquals(expected.getSourceImageId(), restored.getSourceImageId());
            assertEquals(expected.getCreationSourceImageId(), restored.getCreationSourceImageId());
            assertEquals(expected.getCreationSourceImageRegion(), restored.getCreationSourceImageRegion());
            assertEquals(expected.getCreationSourceInstanceId(), restored.getCreationSourceInstanceId());
            assertEquals(expected.getDockerImage(), restored.getDockerImage());
        }
    }

    private static void assertLegacyImage(Image restored) {
        assertEquals("ami-old", restored.getImageId());
        assertEquals(REGION, restored.getRegion());
        assertEquals("ami-catalog", restored.getSourceImageId());
        assertEquals("example/captured:1", restored.getDockerImage());
        assertNull(restored.getCreationSourceImageId());
        assertNull(restored.getCreationSourceImageRegion());
        assertNull(restored.getCreationSourceInstanceId());
    }

    private static List<Image> imagesWithCreationProvenance() {
        Image copied = new Image();
        copied.setImageId("ami-copied");
        copied.setRegion(REGION);
        copied.setSourceImageId("ami-catalog");
        copied.setCreationSourceImageId("ami-immediate-source");
        copied.setCreationSourceImageRegion("us-east-1");

        Image captured = new Image();
        captured.setImageId("ami-captured");
        captured.setRegion(REGION);
        captured.setSourceImageId("ami-catalog");
        captured.setCreationSourceImageId(copied.getImageId());
        captured.setCreationSourceImageRegion(REGION);
        captured.setCreationSourceInstanceId("i-source");
        captured.setDockerImage("example/captured:1");
        return List.of(copied, captured);
    }
}

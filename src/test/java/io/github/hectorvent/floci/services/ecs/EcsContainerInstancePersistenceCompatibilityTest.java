package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.ecs.model.ContainerInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EcsContainerInstancePersistenceCompatibilityTest {

    @Test
    void legacyRunningTaskCountLoadsButIsNotPersistedAgain(@TempDir Path dataDir) throws Exception {
        Path file = dataDir.resolve("container-instances.json");
        Files.writeString(file, """
                {"instance":{"containerInstanceArn":"arn:example:ecs:instance/one",
                "status":"ACTIVE","runningTasksCount":3}}
                """);

        PersistentStorage<String, ContainerInstance> storage = new PersistentStorage<>(file,
                new TypeReference<Map<String, ContainerInstance>>() { });
        storage.load();
        ContainerInstance instance = storage.get("instance").orElseThrow();
        assertEquals("arn:example:ecs:instance/one", instance.getContainerInstanceArn());
        assertEquals("ACTIVE", instance.getStatus());

        storage.flush();
        JsonNode saved = new ObjectMapper().readTree(file.toFile()).path("instance");
        assertFalse(saved.has("runningTasksCount"), "obsolete count must not be written again");
    }
}

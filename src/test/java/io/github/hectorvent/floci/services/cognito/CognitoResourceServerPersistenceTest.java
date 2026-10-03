package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.cognito.model.ResourceServer;
import io.github.hectorvent.floci.services.cognito.model.ResourceServerScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitoResourceServerPersistenceTest {

    private static final String IDENTIFIER = "https://api.example.com";

    @TempDir
    Path directory;

    @Test
    void loadingAnOlderResourceServerRecordIgnoresTheRemovedPrivateField() throws Exception {
        Path file = directory.resolve("resource-servers.json");
        TypeReference<Map<String, ResourceServer>> type = new TypeReference<>() {};
        PersistentStorage<String, ResourceServer> storage = new PersistentStorage<>(file, type);
        CognitoService service = new CognitoService(new InMemoryStorage<>(), new InMemoryStorage<>(), storage,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", new RegionResolver("us-east-1", "000000000000"), null, null);
        String pool = service.createUserPool(Map.of("PoolName", "Persistence test"), "us-east-1").getId();
        String key = pool + "::" + IDENTIFIER;
        ObjectMapper mapper = new ObjectMapper();
        Files.writeString(file, mapper.writeValueAsString(Map.of(key, Map.of(
                "userPoolId", pool, "identifier", IDENTIFIER, "name", "Old API",
                "incarnationId", "removed-private-value", "creationDate", 42,
                "scopes", List.of(Map.of("scopeName", "read", "scopeDescription", "Read access"))))));

        storage.load();

        assertEquals("Old API", service.describeResourceServer(pool, IDENTIFIER).getName());
        assertEquals(42, service.describeResourceServer(pool, IDENTIFIER).getCreationDate());
        ResourceServerScope scope = new ResourceServerScope();
        scope.setScopeName("write");
        scope.setScopeDescription("Write access");
        service.updateResourceServer(pool, IDENTIFIER, "Template API", List.of(scope));
        JsonNode persisted = mapper.readTree(file.toFile()).path(key);
        assertFalse(persisted.has("incarnationId"));
        PersistentStorage<String, ResourceServer> reloaded = new PersistentStorage<>(file, type);
        reloaded.load();
        ResourceServer restored = reloaded.get(key).orElseThrow();
        assertEquals("Template API", restored.getName());
        assertEquals(42, restored.getCreationDate());
        assertEquals("write", restored.getScopes().getFirst().getScopeName());

        service.deleteResourceServer(pool, IDENTIFIER);
        reloaded.load();
        assertTrue(reloaded.get(key).isEmpty());
    }
}

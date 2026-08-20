package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence-across-restart coverage: writes a fully-populated agent runtime through
 * {@link PersistentStorage} to disk and reloads it into a fresh instance (a simulated
 * restart), verifying that JsonNode config blobs, {@code Instant} timestamps, version
 * snapshots, endpoints, tags, and env vars all survive serialization. This is the path
 * exercised by {@code persistent}/{@code hybrid}/{@code wal} storage modes and native image.
 */
class BedrockAgentCorePersistenceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void agentRuntimeSurvivesPersistentStorageRoundTrip(@TempDir Path dir) {
        BedrockAgentCoreControlService svc = new BedrockAgentCoreControlService(
                new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"));

        ObjectNode artifact = mapper.createObjectNode();
        artifact.putObject("containerConfiguration").put("containerUri", "x:latest");
        ObjectNode network = mapper.createObjectNode();
        network.put("networkMode", "PUBLIC");
        ObjectNode authorizer = mapper.createObjectNode();
        authorizer.putObject("customJWTAuthorizer").put("discoveryUrl", "https://issuer/.well-known");

        AgentRuntime created = svc.createAgentRuntime("persistAgent", artifact, network,
                "arn:aws:iam::000000000000:role/agent", "v1", Map.of("K", "V"),
                authorizer, null, null, REGION);
        String id = created.getAgentRuntimeId();
        svc.updateAgentRuntime(id, artifact, network,
                "arn:aws:iam::000000000000:role/agent", "v2", null, authorizer, null, REGION);
        svc.createEndpoint(id, "prod", "1", "prod ep", null, REGION);
        AgentRuntime before = svc.getAgentRuntime(id, REGION);
        svc.tagByArn(REGION, svc.arn(before, "1", REGION), Map.of("env", "prod"));
        before = svc.getAgentRuntime(id, REGION);

        // Write to disk, then reload into a brand-new backend (simulated restart).
        Path file = dir.resolve("bedrock-agentcore-runtimes.json");
        TypeReference<Map<String, AgentRuntime>> ref = new TypeReference<>() {};
        PersistentStorage<String, AgentRuntime> writer = new PersistentStorage<>(file, ref);
        writer.put("runtime:" + REGION + ":" + id, before);
        writer.flush();

        PersistentStorage<String, AgentRuntime> reader = new PersistentStorage<>(file, ref);
        reader.load();
        AgentRuntime loaded = reader.get("runtime:" + REGION + ":" + id).orElseThrow();

        assertEquals("persistAgent", loaded.getAgentRuntimeName());
        assertEquals(2, loaded.getLatestVersion());
        assertEquals(2, loaded.getVersions().size());
        assertNotNull(loaded.getCreatedAt());
        // JsonNode config blobs survive.
        assertEquals("x:latest", loaded.getAgentRuntimeArtifact()
                .path("containerConfiguration").path("containerUri").asText());
        assertEquals("https://issuer/.well-known", loaded.getAuthorizerConfiguration()
                .path("customJWTAuthorizer").path("discoveryUrl").asText());
        // Per-version snapshot JsonNode survives.
        assertNotNull(loaded.getVersions().get(0).getAgentRuntimeArtifact());
        // Env vars + tags + endpoints (DEFAULT + prod) survive.
        assertEquals("V", loaded.getEnvironmentVariables().get("K"));
        assertEquals("prod", loaded.getTags().get("env"));
        assertEquals(2, loaded.getEndpoints().size());
        assertTrue(loaded.getEndpoints().stream().anyMatch(e -> "prod".equals(e.getName())));
        assertTrue(loaded.getEndpoints().stream().anyMatch(e -> "DEFAULT".equals(e.getName())));
    }
}

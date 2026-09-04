package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RegisterTaskDefinition sets {@code volumes} and {@code runtimePlatform} on the task definition
 * <em>after</em> {@link EcsService#registerTaskDefinition} has already stored it. A storage backend
 * that serializes on {@code put} (persistent, hybrid, WAL) captured the pre-mutation value, so
 * without an explicit write-back those fields are lost on restart.
 *
 * <p>Backed by a real {@link PersistentStorage} over a temp directory, so a second service instance
 * reloads from the file the first one wrote — the in-memory backend used elsewhere keeps the live
 * object reference and cannot detect the miss.
 */
class EcsJsonHandlerTaskDefinitionPersistenceTest {

    private static final String REGION = "us-east-1";

    @Test
    void taskDefinitionFieldsSetAfterRegistrationSurviveRestart(@TempDir Path dataDir) throws Exception {
        FileStorageFactory storage = new FileStorageFactory(dataDir);
        ObjectMapper objectMapper = new ObjectMapper();

        EcsJsonHandler handler = new EcsJsonHandler(serviceWithStorage(storage), objectMapper);
        JsonNode request = objectMapper.readTree("""
                {
                  "family": "restart-family",
                  "runtimePlatform": {"cpuArchitecture": "ARM64", "operatingSystemFamily": "LINUX"},
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "mountPoints": [{"sourceVolume": "data", "containerPath": "/data", "readOnly": false}],
                      "logConfiguration": {
                        "logDriver": "awslogs",
                        "options": {"awslogs-group": "/ecs/restart-family"}
                      }
                    }
                  ],
                  "volumes": [{"name": "data", "host": {"sourcePath": "/host/data"}}]
                }
                """);
        Response response = handler.handle("RegisterTaskDefinition", request, REGION);
        assertEquals(200, response.getStatus());

        // Simulate a restart: a fresh service reloading from the files the first one wrote.
        EcsService reloaded = serviceWithStorage(new FileStorageFactory(dataDir));
        TaskDefinition td = reloaded.describeTaskDefinition("restart-family:1", REGION);

        assertNotNull(td.getRuntimePlatform(), "runtimePlatform must survive a restart");
        assertEquals("ARM64", td.getRuntimePlatform().cpuArchitecture());
        assertEquals("LINUX", td.getRuntimePlatform().operatingSystemFamily());

        var logConfiguration = td.getContainerDefinitions().getFirst().getLogConfiguration();
        assertNotNull(logConfiguration, "logConfiguration must survive a restart");
        assertEquals("awslogs", logConfiguration.logDriver());
        assertEquals("/ecs/restart-family", logConfiguration.options().get("awslogs-group"));

        assertEquals(1, td.getVolumes().size(), "task-level volumes must survive a restart");
        assertEquals("/host/data", td.getVolumes().getFirst().hostSourcePath());
    }

    @Test
    void updateServiceForceNewDeploymentMintsNewDeploymentId(@TempDir Path dataDir) throws Exception {
        FileStorageFactory storage = new FileStorageFactory(dataDir);
        ObjectMapper objectMapper = new ObjectMapper();
        EcsService service = serviceWithStorage(storage);
        EcsJsonHandler handler = new EcsJsonHandler(service, objectMapper);

        JsonNode registerReq = objectMapper.readTree("""
                {
                  "family": "force-fam",
                  "containerDefinitions": [{"name": "app", "image": "alpine:latest"}]
                }
                """);
        handler.handle("RegisterTaskDefinition", registerReq, REGION);

        var created = service.createService(null, "force-svc", "force-fam:1", 0,
                LaunchType.FARGATE, List.of(), null, REGION);
        String firstId = created.getDeploymentId();

        ObjectNode req = objectMapper.createObjectNode();
        req.put("service", "force-svc");
        req.put("forceNewDeployment", true);
        Response resp = handler.handle("UpdateService", req, REGION);

        JsonNode svc = objectMapper.readTree(resp.getEntity().toString()).path("service");
        String newId = svc.path("deployments").path(0).path("id").asText();
        assertNotEquals(firstId, newId);
    }

    private static EcsService serviceWithStorage(StorageFactory storage) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                mock(EcsContainerManager.class),
                config,
                mock(EcsLoadBalancerRegistrar.class),
                storage,
                null);
        service.initializeStorage();
        return service;
    }

    /** Backs every store with a real {@link PersistentStorage} file, so values are serialized on put. */
    private static final class FileStorageFactory extends StorageFactory {

        private final Path dataDir;
        private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();

        private FileStorageFactory(Path dataDir) {
            super(null, null);
            this.dataDir = dataDir;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                       String fileName,
                                                       TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> {
                PersistentStorage<String, V> persistent =
                        new PersistentStorage<>(dataDir.resolve(fileName), typeReference);
                persistent.load();
                return new AccountAwareStorageBackend<>(persistent, null, "000000000000");
            });
        }
    }
}

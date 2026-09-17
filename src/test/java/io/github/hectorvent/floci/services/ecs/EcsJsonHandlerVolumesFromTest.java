package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ecs.container.HostVolumePolicy;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EcsJsonHandlerVolumesFromTest {

    private ObjectMapper objectMapper;
    private EcsJsonHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        EcsService service = mock(EcsService.class);
        when(service.registerTaskDefinition(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenAnswer(invocation -> {
                    TaskDefinition taskDefinition = new TaskDefinition();
                    taskDefinition.setFamily(invocation.getArgument(0));
                    taskDefinition.setRevision(1);
                    taskDefinition.setStatus("ACTIVE");
                    taskDefinition.setContainerDefinitions(invocation.getArgument(1, List.class));
                    return taskDefinition;
                });
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        handler = new EcsJsonHandler(service, objectMapper, new HostVolumePolicy(config));
    }

    @Test
    void registerTaskDefinitionRoundTripsVolumesFrom() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {
                  "family": "shared-volume-family",
                  "containerDefinitions": [
                    {"name": "source", "image": "sidecar:latest"},
                    {
                      "name": "app",
                      "image": "app:latest",
                      "volumesFrom": [
                        {"sourceContainer": "source", "readOnly": true},
                        {"sourceContainer": "source"}
                      ]
                    }
                  ]
                }
                """);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode app = objectMapper.valueToTree(response.getEntity())
                .path("taskDefinition").path("containerDefinitions").get(1);

        assertEquals("source", app.path("volumesFrom").get(0).path("sourceContainer").asText());
        assertTrue(app.path("volumesFrom").get(0).path("readOnly").asBoolean());
        assertFalse(app.path("volumesFrom").get(1).path("readOnly").asBoolean());
    }

    @Test
    void containerDefinitionOmitsVolumesFromWhenNotProvided() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {
                  "family": "plain-family",
                  "containerDefinitions": [
                    {"name": "app", "image": "app:latest"}
                  ]
                }
                """);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode app = objectMapper.valueToTree(response.getEntity())
                .path("taskDefinition").path("containerDefinitions").get(0);

        assertTrue(app.path("volumesFrom").isMissingNode());
    }
}

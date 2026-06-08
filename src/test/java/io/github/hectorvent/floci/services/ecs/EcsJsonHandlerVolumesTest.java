package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the RegisterTaskDefinition JSON wire path in {@link EcsJsonHandler}:
 * task-level {@code volumes} and per-container {@code mountPoints} must be parsed from the
 * request, stored on the task definition, and serialized back in the response (round-trip
 * fidelity, faithful to the EC2-launch-type ECS shape {@code volumes[].host.sourcePath}).
 *
 * <p>{@link EcsService} is mocked to echo the parsed container definitions back inside a
 * task definition, so the test exercises parse + serialize without any Docker/Quarkus context.
 */
class EcsJsonHandlerVolumesTest {

    private EcsService service;
    private ObjectMapper objectMapper;
    private EcsJsonHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = mock(EcsService.class);
        // Echo the parsed container definitions (arg index 1) back inside a task definition.
        when(service.registerTaskDefinition(anyString(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenAnswer(inv -> {
                    TaskDefinition td = new TaskDefinition();
                    td.setFamily(inv.getArgument(0));
                    td.setRevision(1);
                    td.setStatus("ACTIVE");
                    td.setContainerDefinitions(inv.getArgument(1, List.class));
                    return td;
                });

        handler = new EcsJsonHandler(service, objectMapper);
    }

    @Test
    void registerTaskDefinitionRoundTripsVolumesAndMountPoints() throws Exception {
        String requestJson = """
                {
                  "family": "volumes-family",
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "mountPoints": [
                        {"sourceVolume": "config-vol", "containerPath": "/app/config.yml", "readOnly": true},
                        {"sourceVolume": "aws-vol", "containerPath": "/root/.aws", "readOnly": false}
                      ]
                    }
                  ],
                  "volumes": [
                    {"name": "config-vol", "host": {"sourcePath": "/host/abs/config.yml"}},
                    {"name": "aws-vol", "host": {"sourcePath": "/host/abs/.aws"}}
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode td = objectMapper.valueToTree(response.getEntity()).path("taskDefinition");

        // Task-level volumes round-trip with the EC2 host.sourcePath shape.
        JsonNode volumes = td.path("volumes");
        assertTrue(volumes.isArray() && volumes.size() == 2, "two volumes expected");
        assertEquals("config-vol", volumes.get(0).path("name").asText());
        assertEquals("/host/abs/config.yml", volumes.get(0).path("host").path("sourcePath").asText());
        assertEquals("aws-vol", volumes.get(1).path("name").asText());
        assertEquals("/host/abs/.aws", volumes.get(1).path("host").path("sourcePath").asText());

        // Per-container mountPoints round-trip with sourceVolume/containerPath/readOnly.
        JsonNode mps = td.path("containerDefinitions").get(0).path("mountPoints");
        assertTrue(mps.isArray() && mps.size() == 2, "two mountPoints expected");
        assertEquals("config-vol", mps.get(0).path("sourceVolume").asText());
        assertEquals("/app/config.yml", mps.get(0).path("containerPath").asText());
        assertTrue(mps.get(0).path("readOnly").asBoolean(), "config mount is read-only");
        assertEquals("aws-vol", mps.get(1).path("sourceVolume").asText());
        assertEquals("/root/.aws", mps.get(1).path("containerPath").asText());
        assertFalse(mps.get(1).path("readOnly").asBoolean(), "aws mount is read-write");
    }
}

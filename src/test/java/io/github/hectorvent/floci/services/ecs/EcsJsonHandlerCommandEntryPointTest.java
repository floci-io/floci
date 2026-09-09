package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the RegisterTaskDefinition JSON wire path in {@link EcsJsonHandler}:
 * per-container {@code command} and {@code entryPoint} must be parsed from the request,
 * stored on the container definition, and serialized back in the response.
 *
 * <p>Both fields were already parsed and honoured when launching the container
 * ({@code EcsContainerManager}), but never rendered by {@code containerDefinitionNode} — so
 * re-registering a task definition from DescribeTaskDefinition output silently dropped them
 * and the new revision fell back to the image's default entrypoint.
 *
 * <p>{@link EcsService} is mocked to echo the parsed container definitions back inside a
 * task definition, so the test exercises parse + serialize without any Docker/Quarkus context.
 */
class EcsJsonHandlerCommandEntryPointTest {

    private EcsService service;
    private ObjectMapper objectMapper;
    private EcsJsonHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = mock(EcsService.class);
        // Echo the parsed container definitions (arg index 1) back inside a task definition.
        when(service.registerTaskDefinition(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), anyString()))
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
    void registerTaskDefinitionRoundTripsCommandAndEntryPoint() throws Exception {
        String requestJson = """
                {
                  "family": "entrypoint-family",
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "entryPoint": ["/bin/sh", "-c"],
                      "command": ["fetch-ca && exec agent --serve"]
                    }
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode container = objectMapper.valueToTree(response.getEntity())
                .path("taskDefinition").path("containerDefinitions").get(0);

        JsonNode entryPoint = container.path("entryPoint");
        assertTrue(entryPoint.isArray() && entryPoint.size() == 2, "entryPoint expected with two elements");
        assertEquals("/bin/sh", entryPoint.get(0).asText());
        assertEquals("-c", entryPoint.get(1).asText());

        JsonNode command = container.path("command");
        assertTrue(command.isArray() && command.size() == 1, "command expected with one element");
        assertEquals("fetch-ca && exec agent --serve", command.get(0).asText());
    }

    @Test
    void containerDefinitionOmitsCommandAndEntryPointWhenNotProvided() throws Exception {
        String requestJson = """
                {
                  "family": "plain-family",
                  "containerDefinitions": [
                    {"name": "app", "image": "alpine:latest"}
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode container = objectMapper.valueToTree(response.getEntity())
                .path("taskDefinition").path("containerDefinitions").get(0);

        // Sparse serialization: absent input stays absent on the response, as real AWS does.
        assertTrue(container.path("entryPoint").isMissingNode(), "entryPoint must stay absent");
        assertTrue(container.path("command").isMissingNode(), "command must stay absent");
    }
}

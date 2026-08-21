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
 * the task-level {@code runtimePlatform} and per-container {@code logConfiguration} must be
 * parsed from the request, stored on the task definition, and serialized back in the response
 * with the AWS wire shape — and must stay absent from the response when the request omitted
 * them, so existing callers see no new keys.
 *
 * <p>{@link EcsService} is mocked to echo the parsed container definitions back inside a
 * task definition, so the test exercises parse + serialize without any Docker/Quarkus context.
 */
class EcsJsonHandlerRuntimePlatformLogConfigurationTest {

    private ObjectMapper objectMapper;
    private EcsJsonHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        EcsService service = mock(EcsService.class);
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
    void registerTaskDefinitionRoundTripsRuntimePlatformAndLogConfiguration() throws Exception {
        String requestJson = """
                {
                  "family": "arm-family",
                  "runtimePlatform": {"cpuArchitecture": "ARM64", "operatingSystemFamily": "LINUX"},
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "logConfiguration": {
                        "logDriver": "awslogs",
                        "options": {
                          "awslogs-group": "/ecs/arm-family",
                          "awslogs-region": "us-east-1",
                          "awslogs-stream-prefix": "ecs"
                        },
                        "secretOptions": [
                          {"name": "splunk-token", "valueFrom": "arn:aws:ssm:us-east-1:000000000000:parameter/splunk"}
                        ]
                      }
                    }
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode td = objectMapper.valueToTree(response.getEntity()).path("taskDefinition");

        JsonNode platform = td.path("runtimePlatform");
        assertEquals("ARM64", platform.path("cpuArchitecture").asText());
        assertEquals("LINUX", platform.path("operatingSystemFamily").asText());

        JsonNode logConfig = td.path("containerDefinitions").get(0).path("logConfiguration");
        assertEquals("awslogs", logConfig.path("logDriver").asText());
        assertEquals("/ecs/arm-family", logConfig.path("options").path("awslogs-group").asText());
        assertEquals("us-east-1", logConfig.path("options").path("awslogs-region").asText());
        assertEquals("ecs", logConfig.path("options").path("awslogs-stream-prefix").asText());
        JsonNode secretOptions = logConfig.path("secretOptions");
        assertTrue(secretOptions.isArray() && secretOptions.size() == 1, "one secretOption expected");
        assertEquals("splunk-token", secretOptions.get(0).path("name").asText());
        assertEquals("arn:aws:ssm:us-east-1:000000000000:parameter/splunk",
                secretOptions.get(0).path("valueFrom").asText());
    }

    @Test
    void registerTaskDefinitionSerializesOnlyTheRuntimePlatformKeysThatWereSent() throws Exception {
        String requestJson = """
                {
                  "family": "arch-only-family",
                  "runtimePlatform": {"cpuArchitecture": "X86_64"},
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "logConfiguration": {"logDriver": "json-file"}
                    }
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode td = objectMapper.valueToTree(response.getEntity()).path("taskDefinition");

        JsonNode platform = td.path("runtimePlatform");
        assertEquals("X86_64", platform.path("cpuArchitecture").asText());
        assertTrue(platform.path("operatingSystemFamily").isMissingNode(),
                "operatingSystemFamily must not be invented when the request omitted it");

        JsonNode logConfig = td.path("containerDefinitions").get(0).path("logConfiguration");
        assertEquals("json-file", logConfig.path("logDriver").asText());
        assertTrue(logConfig.path("options").isMissingNode(), "options must not be emitted when omitted");
        assertTrue(logConfig.path("secretOptions").isMissingNode(), "secretOptions must not be emitted when omitted");
    }

    @Test
    void registerTaskDefinitionWithoutRuntimePlatformOrLogConfigurationEmitsNeitherKey() throws Exception {
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
        JsonNode td = objectMapper.valueToTree(response.getEntity()).path("taskDefinition");

        assertTrue(td.path("runtimePlatform").isMissingNode(),
                "runtimePlatform must stay absent for a task definition that never set it");
        assertTrue(td.path("containerDefinitions").get(0).path("logConfiguration").isMissingNode(),
                "logConfiguration must stay absent for a container that never set it");
    }
}

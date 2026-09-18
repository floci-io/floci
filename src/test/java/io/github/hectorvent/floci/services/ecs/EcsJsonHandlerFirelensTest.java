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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EcsJsonHandlerFirelensTest {

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
    void registerTaskDefinitionRoundTripsFirelensConfiguration() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {
                  "family": "firelens-family",
                  "containerDefinitions": [
                    {
                      "name": "log_router",
                      "image": "public.ecr.aws/aws-observability/aws-for-fluent-bit:3",
                      "firelensConfiguration": {
                        "type": "fluentbit",
                        "options": {"enable-ecs-log-metadata": "false"}
                      }
                    },
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "logConfiguration": {
                        "logDriver": "awsfirelens",
                        "options": {"Name": "cloudwatch_logs", "log_group_name": "/ecs/app"}
                      }
                    }
                  ]
                }
                """);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode taskDefinition = objectMapper.valueToTree(response.getEntity()).path("taskDefinition");

        JsonNode firelens = taskDefinition.path("containerDefinitions").get(0).path("firelensConfiguration");
        assertEquals("fluentbit", firelens.path("type").asText());
        assertEquals("false", firelens.path("options").path("enable-ecs-log-metadata").asText());
        JsonNode logConfiguration = taskDefinition.path("containerDefinitions").get(1).path("logConfiguration");
        assertEquals("awsfirelens", logConfiguration.path("logDriver").asText());
        assertEquals("cloudwatch_logs", logConfiguration.path("options").path("Name").asText());
        assertTrue(logConfiguration.path("options").path("log_group_name").isTextual());
    }
}

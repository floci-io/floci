package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the RegisterTaskDefinition JSON wire path in {@link EcsJsonHandler}:
 * container-level {@code healthCheck} must be parsed from the request, stored on the
 * container definition, and serialized back in the response with the AWS wire shape,
 * and must stay absent from the response when the request omitted it.
 */
class EcsJsonHandlerHealthCheckTest {

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
    void registerTaskDefinitionRoundTripsHealthCheck() throws Exception {
        String requestJson = """
                {
                  "family": "healthcheck-demo",
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "public.ecr.aws/docker/library/nginx:alpine",
                      "essential": true,
                      "healthCheck": {
                        "command": ["CMD-SHELL", "curl -f http://localhost/ || exit 1"],
                        "interval": 30,
                        "timeout": 5,
                        "retries": 3,
                        "startPeriod": 10
                      }
                    }
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode td = objectMapper.valueToTree(response.getEntity()).path("taskDefinition");

        JsonNode container = td.path("containerDefinitions").get(0);
        JsonNode hc = container.path("healthCheck");
        assertTrue(hc.isObject(), "healthCheck should be an object");
        assertTrue(hc.path("command").isArray(), "command should be an array");
        assertEquals("CMD-SHELL", hc.path("command").get(0).asText());
        assertEquals("curl -f http://localhost/ || exit 1", hc.path("command").get(1).asText());
        assertEquals(30, hc.path("interval").asInt());
        assertEquals(5, hc.path("timeout").asInt());
        assertEquals(3, hc.path("retries").asInt());
        assertEquals(10, hc.path("startPeriod").asInt());
    }

    @Test
    void registerTaskDefinitionOmitsHealthCheckWhenNotProvided() throws Exception {
        String requestJson = """
                {
                  "family": "plain-family",
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest"
                    }
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode td = objectMapper.valueToTree(response.getEntity()).path("taskDefinition");

        JsonNode container = td.path("containerDefinitions").get(0);
        assertTrue(container.path("healthCheck").isMissingNode(),
                "healthCheck must stay absent when omitted");
    }

    @Test
    void registerTaskDefinitionHandlesPartialHealthCheckFields() throws Exception {
        String requestJson = """
                {
                  "family": "partial-hc-family",
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "healthCheck": {
                        "command": ["CMD", "/bin/check.sh"]
                      }
                    }
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode td = objectMapper.valueToTree(response.getEntity()).path("taskDefinition");

        JsonNode container = td.path("containerDefinitions").get(0);
        JsonNode hc = container.path("healthCheck");
        assertTrue(hc.isObject());
        assertEquals(2, hc.path("command").size());
        assertEquals("CMD", hc.path("command").get(0).asText());
        assertEquals("/bin/check.sh", hc.path("command").get(1).asText());
        assertTrue(hc.path("interval").isMissingNode(), "interval should be absent");
        assertTrue(hc.path("timeout").isMissingNode(), "timeout should be absent");
        assertTrue(hc.path("retries").isMissingNode(), "retries should be absent");
        assertTrue(hc.path("startPeriod").isMissingNode(), "startPeriod should be absent");
    }

    @Test
    void registerTaskDefinitionWithMissingHealthCheckCommandThrowsClientException() throws Exception {
        String requestJson = """
                {
                  "family": "missing-hc-command",
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "healthCheck": {
                        "interval": 30
                      }
                    }
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        AwsException ex = assertThrows(AwsException.class, () ->
                handler.handle("RegisterTaskDefinition", request, "us-east-1"));
        assertEquals("ClientException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void registerTaskDefinitionWithEmptyHealthCheckCommandThrowsClientException() throws Exception {
        String requestJson = """
                {
                  "family": "empty-hc-command",
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "healthCheck": {
                        "command": []
                      }
                    }
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        AwsException ex = assertThrows(AwsException.class, () ->
                handler.handle("RegisterTaskDefinition", request, "us-east-1"));
        assertEquals("ClientException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }
}

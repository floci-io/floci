package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.lambda.LambdaController;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerJsonHandler;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SdkManagementTaskInvokerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EcsJsonHandler ecs = mock(EcsJsonHandler.class);
    private final ServiceRegistry registry = mock(ServiceRegistry.class);
    private final SdkManagementTaskInvoker invoker = new SdkManagementTaskInvoker(ecs,
            mock(LambdaController.class), mock(SecretsManagerJsonHandler.class), registry, mapper);

    @Test
    void recasesModeledEcsMembersWithoutChangingLogOptionKeysOrValues() throws Exception {
        when(registry.isServiceEnabled("ecs")).thenReturn(true);
        JsonNode response = mapper.readTree("""
                {"service":{"createdAt":1.5,"serviceConnectConfiguration":{
                  "logConfiguration":{"options":{"CreatedAt":42,"lowercase-key":"value"}}}}}
                """);
        when(ecs.handle(eq("UpdateService"), any(), eq("eu-west-2")))
                .thenReturn(Response.ok(response).build());
        JsonNode request = mapper.readTree("""
                {"Cluster":"example","Service":"example","ServiceConnectConfiguration":{
                  "LogConfiguration":{"Options":{"CreatedAt":42,"lowercase-key":"value"}}}}
                """);
        JsonNode result = invoker.invoke(integration(), request, "eu-west-2");

        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        verify(ecs).handle(eq("UpdateService"), input.capture(), eq("eu-west-2"));
        assertEquals(request.path("ServiceConnectConfiguration").path("LogConfiguration").path("Options"),
                input.getValue().path("serviceConnectConfiguration").path("logConfiguration").path("options"));
        assertEquals("1970-01-01T00:00:01.500Z", result.path("Service").path("CreatedAt").asText());
        assertEquals(response.path("service").path("serviceConnectConfiguration").path("logConfiguration").path("options"),
                result.path("Service").path("ServiceConnectConfiguration").path("LogConfiguration").path("Options"));
    }

    @Test
    void rendersTaskSetTimestampsAsIsoStrings() throws Exception {
        when(registry.isServiceEnabled("ecs")).thenReturn(true);
        JsonNode response = mapper.readTree("""
                {"services":[{"serviceName":"example","taskSets":[{"id":"ecs-svc/1",
                  "createdAt":1.5,"updatedAt":2,"stabilityStatusAt":3.25}]}]}
                """);
        when(ecs.handle(eq("DescribeServices"), any(), eq("us-east-1")))
                .thenReturn(Response.ok(response).build());
        JsonNode result = invoker.invoke(
                StatesIntegration.parse("arn:aws:states:::aws-sdk:ecs:describeServices").orElseThrow(),
                mapper.readTree("{\"Cluster\":\"example\",\"Services\":[\"example\"]}"), "us-east-1");

        JsonNode taskSet = result.path("Services").path(0).path("TaskSets").path(0);
        assertEquals("1970-01-01T00:00:01.500Z", taskSet.path("CreatedAt").asText());
        assertEquals("1970-01-01T00:00:02.000Z", taskSet.path("UpdatedAt").asText());
        assertEquals("1970-01-01T00:00:03.250Z", taskSet.path("StabilityStatusAt").asText());
    }

    @Test
    void aDisabledServiceFailsBeforeCallingItsHandler() {
        AslExecutor.FailStateException error = assertThrows(AslExecutor.FailStateException.class,
                () -> invoker.invoke(integration(), mapper.createObjectNode(), "us-east-1"));
        assertEquals("Ecs.ServiceNotAvailableException", error.error);
        verifyNoInteractions(ecs);
    }

    @Test
    void handlerErrorResponsesBecomeCatchableSdkExceptions() {
        when(registry.isServiceEnabled("ecs")).thenReturn(true);
        when(ecs.handle(anyString(), any(), anyString())).thenReturn(Response.status(400)
                .entity(new AwsErrorResponse("ClientException", "synthetic failure")).build());
        AslExecutor.FailStateException error = assertThrows(AslExecutor.FailStateException.class,
                () -> invoker.invoke(integration(), mapper.createObjectNode(), "us-east-1"));
        assertEquals("Ecs.ClientException", error.error);
        assertEquals("synthetic failure", error.cause);
    }

    private static StatesIntegration integration() {
        return StatesIntegration.parse("arn:aws:states:::aws-sdk:ecs:updateService").orElseThrow();
    }
}

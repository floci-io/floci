package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.Resource;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.ConnectionAuthorizationType;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Step Functions HTTP Task")
class StepFunctionsHttpInvokeNativeTest {

    private static final String ROLE_ARN = System.getenv("SFN_ROLE_ARN") != null
            ? System.getenv("SFN_ROLE_ARN")
            : "arn:aws:iam::000000000000:role/service-role/test-role";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("HTTP Task output reaches ResultSelector in the native image")
    void httpTaskResultIsSelectableInNativeImage() throws Exception {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Floci native-image regression test");

        try (ApiGatewayClient apiGateway = TestFixtures.apiGatewayClient();
             EventBridgeClient eventBridge = TestFixtures.eventBridgeClient();
             SfnClient sfn = TestFixtures.sfnClient()) {
            // Each delete is registered as soon as its resource exists and runs in reverse order.
            Deque<Runnable> cleanup = new ArrayDeque<>();
            Throwable failure = null;
            try {
                String apiId = apiGateway.createRestApi(request -> request
                        .name(TestFixtures.uniqueName("sfn-http-fixture"))).id();
                cleanup.push(() -> apiGateway.deleteRestApi(request -> request.restApiId(apiId)));

                String rootId = apiGateway.getResources(request -> request.restApiId(apiId)).items().stream()
                        .filter(resource -> "/".equals(resource.path()))
                        .findFirst()
                        .map(Resource::id)
                        .orElseThrow();
                mockGet(apiGateway, apiId, rootId, "json", "{\"ok\": true, \"n\": 7}", Map.of("X-Probe", "'one'"));
                mockGet(apiGateway, apiId, rootId, "empty", "", Map.of());
                String deploymentId = apiGateway.createDeployment(request -> request.restApiId(apiId)).id();
                apiGateway.createStage(request -> request.restApiId(apiId).stageName("test").deploymentId(deploymentId));

                String connectionName = TestFixtures.uniqueName("sfn-http-connection");
                String connectionArn = eventBridge.createConnection(request -> request
                        .name(connectionName)
                        .authorizationType(ConnectionAuthorizationType.API_KEY)
                        .authParameters(auth -> auth.apiKeyAuthParameters(key -> key
                                .apiKeyName("x-api-key")
                                .apiKeyValue("fixture")))).connectionArn();
                cleanup.push(() -> eventBridge.deleteConnection(request -> request.name(connectionName)));

                String stateMachineArn = sfn.createStateMachine(request -> request
                        .name(TestFixtures.uniqueName("sfn-http-native"))
                        .definition(definition(TestFixtures.endpoint() + "/execute-api/" + apiId + "/test", connectionArn))
                        .roleArn(ROLE_ARN)).stateMachineArn();
                cleanup.push(() -> sfn.deleteStateMachine(request -> request.stateMachineArn(stateMachineArn)));

                String executionArn = sfn.startExecution(request -> request
                        .stateMachineArn(stateMachineArn)
                        .input("{}")).executionArn();
                DescribeExecutionResponse execution =
                        TestFixtures.awaitExecutionTerminal(sfn, executionArn, Duration.ofSeconds(60));

                assertThat(execution.status())
                        .as("%s: %s", execution.error(), execution.cause())
                        .isEqualTo(ExecutionStatus.SUCCEEDED);
                JsonNode output = MAPPER.readTree(execution.output());
                JsonNode json = output.path("json");
                assertThat(json.path("status").asInt()).isEqualTo(200);
                assertThat(json.path("body").path("ok").asBoolean()).isTrue();
                assertThat(json.path("body").path("n").asInt()).isEqualTo(7);
                JsonNode probe = header(json.path("headers"), "X-Probe");
                assertThat(probe.isArray()).as("X-Probe header %s", probe).isTrue();
                assertThat(probe).hasSize(1);
                assertThat(probe.get(0).asText()).isEqualTo("one");
                JsonNode empty = output.path("empty");
                assertThat(empty.path("status").asInt()).isEqualTo(200);
                assertThat(empty.has("body")).isTrue();
                assertThat(empty.get("body").isNull()).as("empty body %s", empty.get("body")).isTrue();
            } catch (Throwable t) {
                failure = t;
                throw t;
            } finally {
                runCleanup(cleanup, failure);
            }
        }
    }

    private static String definition(String base, String connectionArn) {
        return """
                {
                  "StartAt": "Json",
                  "States": {
                    "Json": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::http:invoke",
                      "Parameters": {
                        "ApiEndpoint": "%1$s/json",
                        "Method": "GET",
                        "InvocationConfig": { "ConnectionArn": "%2$s" }
                      },
                      "ResultSelector": {
                        "status.$": "$.StatusCode",
                        "headers.$": "$.Headers",
                        "body.$": "$.ResponseBody"
                      },
                      "ResultPath": "$.json",
                      "Next": "Empty"
                    },
                    "Empty": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::http:invoke",
                      "Parameters": {
                        "ApiEndpoint": "%1$s/empty",
                        "Method": "GET",
                        "InvocationConfig": { "ConnectionArn": "%2$s" }
                      },
                      "ResultSelector": {
                        "status.$": "$.StatusCode",
                        "body.$": "$.ResponseBody"
                      },
                      "ResultPath": "$.empty",
                      "End": true
                    }
                  }
                }
                """.formatted(base, connectionArn);
    }

    // Attempts every registered delete. Failures join the test's own failure, or fail the test when
    // it otherwise passed.
    private static void runCleanup(Deque<Runnable> cleanup, Throwable failure) {
        List<RuntimeException> errors = new ArrayList<>();
        while (!cleanup.isEmpty()) {
            try {
                cleanup.pop().run();
            } catch (RuntimeException e) {
                errors.add(e);
            }
        }
        if (errors.isEmpty()) {
            return;
        }
        if (failure != null) {
            errors.forEach(failure::addSuppressed);
            return;
        }
        RuntimeException aggregate = new RuntimeException("HTTP Task fixture cleanup failed");
        errors.forEach(aggregate::addSuppressed);
        throw aggregate;
    }

    private static void mockGet(ApiGatewayClient apiGateway, String apiId, String rootId, String path,
                                String responseTemplate, Map<String, String> headers) {
        String resourceId = apiGateway.createResource(request -> request
                .restApiId(apiId)
                .parentId(rootId)
                .pathPart(path)).id();
        apiGateway.putMethod(request -> request
                .restApiId(apiId)
                .resourceId(resourceId)
                .httpMethod("GET")
                .authorizationType("NONE"));
        apiGateway.putIntegration(request -> request
                .restApiId(apiId)
                .resourceId(resourceId)
                .httpMethod("GET")
                .type(IntegrationType.MOCK)
                .requestTemplates(Map.of("application/json", "{\"statusCode\": 200}")));
        apiGateway.putMethodResponse(request -> request
                .restApiId(apiId)
                .resourceId(resourceId)
                .httpMethod("GET")
                .statusCode("200")
                .responseParameters(headers.keySet().stream()
                        .collect(Collectors.toMap(name -> "method.response.header." + name, name -> true))));
        apiGateway.putIntegrationResponse(request -> request
                .restApiId(apiId)
                .resourceId(resourceId)
                .httpMethod("GET")
                .statusCode("200")
                .responseParameters(headers.entrySet().stream()
                        .collect(Collectors.toMap(entry -> "method.response.header." + entry.getKey(), Map.Entry::getValue)))
                .responseTemplates(Map.of("application/json", responseTemplate)));
    }

    // Header names keep the fixture's case on Floci; HTTP makes them case-insensitive.
    private static JsonNode header(JsonNode headers, String name) {
        for (Iterator<String> names = headers.fieldNames(); names.hasNext(); ) {
            String candidate = names.next();
            if (candidate.equalsIgnoreCase(name)) {
                return headers.get(candidate);
            }
        }
        return MAPPER.missingNode();
    }
}

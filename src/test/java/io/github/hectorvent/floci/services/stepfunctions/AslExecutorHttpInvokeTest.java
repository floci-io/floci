package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@QuarkusTest
class AslExecutorHttpInvokeTest {

    private static final String REGION = "us-west-2";
    private static final String ACCOUNT = "000000000000";
    private static final String CONNECTION_ARN =
            "arn:aws:events:%s:%s:connection/test/11111111-1111-1111-1111-111111111111"
                    .formatted(REGION, ACCOUNT);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<RecordedRequest> receivedRequests = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private String baseUrl;
    private AslExecutor executor;

    @Inject
    Vertx vertx;

    private record RecordedRequest(String method, String path, String query,
                                   Map<String, List<String>> headers, String body) {
        String firstHeader(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .findFirst()
                    .map(entry -> entry.getValue().isEmpty() ? null : entry.getValue().get(0))
                    .orElse(null);
        }

        List<String> headerValues(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .findFirst()
                    .map(Map.Entry::getValue)
                    .orElse(List.of());
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        executor = new AslExecutor(
            mock(LambdaExecutorService.class),
            mock(LambdaFunctionStore.class),
            mock(DynamoDbService.class),
            mock(DynamoDbJsonHandler.class),
            mock(SqsJsonHandler.class),
            mock(io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler.class),
            mock(io.github.hectorvent.floci.services.ec2.Ec2Service.class),
            mock(io.github.hectorvent.floci.services.s3.S3Service.class),
            mock(EcsService.class),
            mock(EcsJsonHandler.class),
            objectMapper,
            new JsonataEvaluator(objectMapper),
            mock(Instance.class),
            vertx);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void getWithHeadersAndQueryParametersReturnsStructuredResponse() throws Exception {
        Execution execution = run("""
                {
                  "StartAt": "CallHttp",
                  "States": {
                    "CallHttp": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::http:invoke",
                      "Parameters": {
                        "ApiEndpoint": "%s/json",
                        "Method": "GET",
                        "InvocationConfig": {
                          "ConnectionArn": "%s"
                        },
                        "Headers": {
                          "X-Custom": ["one", "two"]
                        },
                        "QueryParameters": {
                          "q": "hello",
                          "tag": ["blue", "green"],
                          "ignored": null
                        }
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(baseUrl, CONNECTION_ARN), "{}");

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertEquals(200, output.path("StatusCode").asInt());
        assertTrue(output.path("ResponseBody").path("ok").asBoolean());
        assertEquals("GET", output.path("ResponseBody").path("method").asText());
        assertTrue(output.path("Headers").has("Content-type"));

        RecordedRequest request = onlyRequest();
        assertEquals("GET", request.method());
        assertEquals("/json", request.path());
        assertQueryContains(request.query(), "q=hello");
        assertQueryContains(request.query(), "tag=blue");
        assertQueryContains(request.query(), "tag=green");

        assertFalse(request.query().contains("ignored"));
        assertEquals(List.of("one", "two"), request.headerValues("X-Custom"));
    }

    @Test
    void postJsonRequestBodyCanUseDynamicParametersAndReturnsTextResponseBody() throws Exception {
        Execution execution = run("""
                {
                  "StartAt": "CallHttp",
                  "States": {
                    "CallHttp": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::http:invoke",
                      "Parameters": {
                        "ApiEndpoint.$": "$.endpoint",
                        "Method.$": "$.method",
                        "Authentication": {
                          "ConnectionArn": "%s"
                        },
                        "RequestBody": {
                          "customerId.$": "$.customerId",
                          "active": true
                        }
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(CONNECTION_ARN), """
                {
                  "endpoint": "%s/text",
                  "method": "POST",
                  "customerId": "cust-123"
                }
                """.formatted(baseUrl));

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertEquals(201, output.path("StatusCode").asInt());
        assertEquals("created", output.path("ResponseBody").asText());

        RecordedRequest request = onlyRequest();
        assertEquals("POST", request.method());
        assertEquals("application/json", request.firstHeader("Content-Type"));
        JsonNode body = objectMapper.readTree(request.body());
        assertEquals("cust-123", body.path("customerId").asText());
        assertTrue(body.path("active").asBoolean());
    }

    @Test
    void nonSuccessStatusFailsWithStatesHttpStatusCodeError() throws Exception {
        Execution execution = run("""
                {
                  "StartAt": "CallHttp",
                  "States": {
                    "CallHttp": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::http:invoke",
                      "Parameters": {
                        "ApiEndpoint": "%s/fail",
                        "Method": "GET",
                        "InvocationConfig": {
                          "ConnectionArn": "%s"
                        }
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(baseUrl, CONNECTION_ARN), "{}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Http.StatusCode.429", execution.getError());
        assertEquals("slow down", execution.getCause());
        assertEquals(1, receivedRequests.size());
    }

    @Test
    void forbiddenHeadersFailBeforeSendingRequest() {
        Execution execution = run("""
                {
                  "StartAt": "CallHttp",
                  "States": {
                    "CallHttp": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::http:invoke",
                      "Parameters": {
                        "ApiEndpoint": "%s/json",
                        "Method": "GET",
                        "InvocationConfig": {
                          "ConnectionArn": "%s"
                        },
                        "Headers": {
                          "Authorization": "Bearer token"
                        }
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(baseUrl, CONNECTION_ARN), "{}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertTrue(execution.getCause().contains("Authorization"));
        assertEquals(0, receivedRequests.size());
    }

    @Test
    void connectionArnIsRequiredBeforeSendingRequest() {
        Execution execution = run("""
                {
                  "StartAt": "CallHttp",
                  "States": {
                    "CallHttp": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::http:invoke",
                      "Parameters": {
                        "ApiEndpoint": "%s/json",
                        "Method": "GET"
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(baseUrl), "{}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertTrue(execution.getCause().contains("ConnectionArn is required"));
        assertEquals(0, receivedRequests.size());
    }

    private Execution run(String definition, String input) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("http-invoke-test");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:http-invoke-test"
                .formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("http-invoke-test-execution");
        execution.setExecutionArn("arn:aws:states:%s:%s:execution:http-invoke-test:http-invoke-test-execution"
                .formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

        List<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }

    private RecordedRequest onlyRequest() {
        assertEquals(1, receivedRequests.size(), "expected exactly one backend request");
        return receivedRequests.get(0);
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        receivedRequests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                copyHeaders(exchange.getRequestHeaders()),
                new String(bodyBytes, StandardCharsets.UTF_8)));

        switch (exchange.getRequestURI().getPath()) {
            case "/json" -> send(exchange, 200, "application/json",
                    """
                    {"ok":true,"method":"%s","path":"%s"}
                    """.formatted(exchange.getRequestMethod(), exchange.getRequestURI().getPath()));
            case "/text" -> send(exchange, 201, "text/plain", "created");
            case "/fail" -> send(exchange, 429, "text/plain", "slow down");
            default -> send(exchange, 404, "text/plain", "not found");
        }
    }

    private Map<String, List<String>> copyHeaders(Headers headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return copy;
    }

    private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.getResponseHeaders().add("X-Test-Backend", "true");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    // TODO Can use Hamcrest
    private void assertQueryContains(String query, String expected) {
        assertNotNull(query);
        assertTrue(query.contains(expected), "expected query to contain " + expected + " but was " + query);
    }
}

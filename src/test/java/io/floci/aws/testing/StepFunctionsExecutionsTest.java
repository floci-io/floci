package io.floci.aws.testing;

import com.sun.net.httpserver.HttpServer;
import io.restassured.RestAssured;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepFunctionsExecutionsTest {

    private static final String EXECUTION_ARN = "arn:aws:states:eu-west-3:000000000000:execution:machine:run";
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger descriptions = new AtomicInteger();
    private HttpServer server;
    private String previousBaseUri;
    private int previousPort;
    private RestAssuredConfig previousConfig;
    private volatile String terminalStatus = "SUCCEEDED";
    private volatile boolean requireRegion = true;

    @BeforeEach
    void startServer() throws Exception {
        previousBaseUri = RestAssured.baseURI;
        previousPort = RestAssured.port;
        previousConfig = RestAssured.config;
        RestAssuredJsonUtils.configureAwsContentTypes();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String action = exchange.getRequestHeaders().getFirst("X-Amz-Target");
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            requests.add(action);
            authorizationHeaders.add(authorization);
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            String body;
            if (requireRegion && (authorization == null || !authorization.contains("/eu-west-3/states/"))) {
                status = 400;
                body = "{\"__type\":\"InvalidArn\"}";
            } else {
                body = switch (action) {
                    case "AWSStepFunctions.CreateStateMachine" ->
                            "{\"stateMachineArn\":\"arn:aws:states:eu-west-3:000000000000:stateMachine:machine\"}";
                    case "AWSStepFunctions.StartExecution" -> "{\"executionArn\":\"" + EXECUTION_ARN + "\"}";
                    case "AWSStepFunctions.DescribeExecution" -> {
                        if (!request.contains(EXECUTION_ARN)) {
                            status = 400;
                            yield "{\"__type\":\"InvalidArn\"}";
                        }
                        String state = descriptions.incrementAndGet() == 1 ? "RUNNING" : terminalStatus;
                        yield "{\"status\":\"" + state + "\"}";
                    }
                    default -> throw new IllegalArgumentException(action);
                };
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (exchange) {
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        RestAssured.baseURI = previousBaseUri;
        RestAssured.port = previousPort;
        RestAssured.config = previousConfig;
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "TIMED_OUT", "ABORTED"})
    void retainsRegionForEveryPollAndReturnsAnyTerminalState(String status) {
        terminalStatus = status;
        Response response = StepFunctionsExecutions.runInRegion("eu-west-3", "{}");
        assertEquals(status, response.jsonPath().getString("status"));
        assertEquals(2, descriptions.get());
        assertEquals(List.of("AWSStepFunctions.CreateStateMachine", "AWSStepFunctions.StartExecution",
                "AWSStepFunctions.DescribeExecution", "AWSStepFunctions.DescribeExecution"), requests);
    }

    @Test
    void existingUnsignedDescribeRemainsAvailable() {
        requireRegion = false;
        Response response = StepFunctionsExecutions.describeExecution(EXECUTION_ARN);
        assertEquals("RUNNING", response.jsonPath().getString("status"));
        assertTrue(requests.contains("AWSStepFunctions.DescribeExecution"));
        assertNull(authorizationHeaders.getFirst());
    }

    @Test
    void existingUnsignedWaitStillReturnsTheTerminalResponse() {
        requireRegion = false;
        terminalStatus = "FAILED";
        Response response = StepFunctionsExecutions.waitForTerminalState(EXECUTION_ARN);
        assertEquals("FAILED", response.jsonPath().getString("status"));
        assertEquals(2, descriptions.get());
        assertEquals(List.of("AWSStepFunctions.DescribeExecution", "AWSStepFunctions.DescribeExecution"), requests);
        assertNull(authorizationHeaders.get(0));
        assertNull(authorizationHeaders.get(1));
    }
}

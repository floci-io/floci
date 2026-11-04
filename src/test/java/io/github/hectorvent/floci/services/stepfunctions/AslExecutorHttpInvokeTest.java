package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeInvoker;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.MultiMap;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.core.http.HttpServer;
import io.vertx.mutiny.core.http.HttpServerRequest;
import io.vertx.mutiny.ext.web.Router;
import io.vertx.mutiny.ext.web.RoutingContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AslExecutorHttpInvokeTest {

    private static final String REGION = "us-west-2";
    private static final String ACCOUNT = "000000000000";
    private static final String MISSING_CONNECTION_ARN =
            "arn:aws:events:%s:%s:connection/missing/11111111-1111-1111-1111-111111111111"
                    .formatted(REGION, ACCOUNT);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<RecordedRequest> receivedRequests = new CopyOnWriteArrayList<>();
    private final List<String> connectionNames = new ArrayList<>();

    private HttpServer server;
    private String baseUrl;
    private AslExecutor executor;
    private String connectionArn;

    @Inject
    Vertx vertx;

    @Inject
    EmulatorConfig emulatorConfig;

    @Inject
    EventBridgeService eventBridgeService;

    @Inject
    EventBridgeInvoker eventBridgeInvoker;

    private record RecordedRequest(String method, String path, String query,
                                   MultiMap headers, String body) {
        String firstHeader(String name) {
            return headers.entries().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .findFirst()
                    .map(entry -> entry.getValue().isEmpty() ? null : entry.getValue())
                    .orElse(null);
        }

        List<String> headerValues(String name) {
            return headers.entries().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .toList();
        }
    }

    @BeforeEach
    void setUp() {
        server = vertx.createHttpServer();

        Router router = Router.router(vertx);
        router.get("/json")
            .handler(ctx -> {
                ctx.request().body()
                    .onItem().invoke(body -> {
                        recordRequest(ctx, body.toString());
                    })
                    .onItem().call(() -> {
                        return ctx.response()
                            .putHeader("Content-Type", "application/json")
                            .end(Buffer.newInstance(JsonObject.of("ok", "true").toBuffer()));
                    })
                    .subscribe().with(_ -> {
                    });
            });
        router.post("/text")
            .handler(ctx -> {
                ctx.request().body()
                    .onItem().invoke(body -> {
                        recordRequest(ctx, body.toString());
                    })
                    .onItem().call(() -> {
                        return ctx.response()
                            .setStatusCode(201)
                            .putHeader("Content-Type", "text/plain")
                            .end();
                    })
                    .subscribe().with(_ -> {
                    });
            });
        router.get("/fail")
            .handler(ctx -> {
                ctx.request().body()
                    .onItem().invoke(body -> {
                        recordRequest(ctx, body.toString());
                    })
                    .onItem().call(() -> {
                        return ctx.response()
                            .setStatusCode(429)
                            .putHeader("Content-Type", "text/plain")
                            .end("slow down");
                    })
                    .subscribe().with(_ -> {
                    });
            });
        router.get("/jsonslow")
            .handler(ctx -> {
                Uni.createFrom().voidItem()
                    .onItem().delayIt().by(Duration.ofSeconds(2))
                    .onItem().transformToUni(ignore -> ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .end(Buffer.newInstance(JsonObject.of("ok", "true").toBuffer())))
                    .subscribe().with(it -> {
                    });
            });
        router.post("/token-slow")
            .handler(ctx -> {
                Uni.createFrom().voidItem()
                    .onItem().delayIt().by(Duration.ofSeconds(2))
                    .onItem().transformToUni(ignore -> ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .end(Buffer.newInstance(JsonObject.of("access_token", "token").toBuffer())))
                    .subscribe().with(it -> {
                    });
            });
        router.get("/form")
            .handler(ctx -> {
                ctx.response()
                    .putHeader("Content-Type", "application/x-www-form-urlencoded")
                    .endAndForget("no_support");
            });
        router.errorHandler(500, ctx -> ctx.response()
            .setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .endAndForget(Buffer.newInstance(JsonObject.of("ok", "false").toBuffer()))
        );
        server.requestHandler(router)
            .listenAndAwait(0, "127.0.0.1");
        baseUrl = "http://127.0.0.1:" + server.actualPort();

        executor = AslExecutorFixture.builder()
                .objectMapper(objectMapper)
                .config(emulatorConfig)
                .vertx(vertx)
                .customResourceLiveness(null)
                .eventBridgeInvoker(eventBridgeInvoker)
                .build();
        connectionArn = createConnection("BASIC", basicAuth(""));
    }

    private void recordRequest(final RoutingContext ctx, String body) {
        HttpServerRequest request = ctx.request();
        RecordedRequest requestRecord = new RecordedRequest(
            request.method().name(),
            request.uri(),
            request.query(),
            request.headers(),
            body);
        Log.info("sss"+ requestRecord);
        receivedRequests.add(requestRecord);
    }

    @AfterEach
    void tearDown() {
        server.closeAndAwait();
        connectionNames.forEach(name -> eventBridgeService.deleteConnection(name, REGION));
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
                """.formatted(baseUrl, connectionArn), "{}");

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertEquals(200, output.path("StatusCode").asInt());
        assertTrue(output.path("ResponseBody").path("ok").asBoolean());
        assertTrue(output.path("Headers").has("Content-Type"));

        RecordedRequest request = onlyRequest();
        assertEquals("GET", request.method());
        assertEquals("/json?q=hello&tag=blue&tag=green", request.path());
        assertQueryContains(request.query(), "q=hello");
        assertQueryContains(request.query(), "tag=blue");
        assertQueryContains(request.query(), "tag=green");

        assertFalse(request.query().contains("ignored"));
        assertEquals(List.of("one", "two"), request.headerValues("X-Custom"));
        assertEquals(List.of("Amazon|StepFunctions|HttpInvoke|" + REGION), request.headerValues("User-Agent"));
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
                        "Headers": {
                          "User-Agent": "custom-client/1.0"
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
                """.formatted(connectionArn), """
                {
                  "endpoint": "%s/text",
                  "method": "POST",
                  "customerId": "cust-123"
                }
                """.formatted(baseUrl));

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertEquals(201, output.path("StatusCode").asInt());

        RecordedRequest request = onlyRequest();
        assertEquals("POST", request.method());
        assertEquals(List.of("Amazon|StepFunctions|HttpInvoke|" + REGION), request.headerValues("User-Agent"));
        assertEquals("application/json", request.firstHeader("Content-Type"));
        JsonNode body = objectMapper.readTree(request.body());
        assertEquals("cust-123", body.path("customerId").asText());
        assertTrue(body.path("active").asBoolean());
    }

    @Test
    void jsonataArgumentsSendAnExplicitNull() throws Exception {
        // AWS keeps the null in the arguments a Task is invoked with: TestState TRACE reports
        // afterArguments {"FunctionName":"nope","Payload":{"v":null}} for Payload {"v":"{% null %}"}.
        Execution execution = run("""
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "CallHttp",
                  "States": {
                    "CallHttp": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::http:invoke",
                      "Arguments": {
                        "ApiEndpoint": "%s/text",
                        "Method": "POST",
                        "Authentication": {
                          "ConnectionArn": "%s"
                        },
                        "RequestBody": {
                          "fromInput": "{%% $lookup($states.input, 'customerId') %%}",
                          "active": true
                        }
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(baseUrl, connectionArn), """
                {
                  "customerId": null
                }
                """);

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        JsonNode body = objectMapper.readTree(onlyRequest().body());
        assertTrue(body.path("fromInput").isNull(), body.toString());
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
                """.formatted(baseUrl, connectionArn), "{}");

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
                """.formatted(baseUrl, connectionArn), "{}");

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

    @Test
    void httpTimeoutFailsStepOnSlowApi() {
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
                        "TimeoutSeconds": 1,
                        "Authentication": {
                          "ConnectionArn": "%s"
                        }
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(connectionArn), """
                {
                  "endpoint": "%s/jsonslow",
                  "method": "GET",
                  "customerId": "cust-123"
                }
                """.formatted(baseUrl));

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Http.Socket", execution.getError());
        assertThat(execution.getCause(), matchesPattern(
                "The timeout period of \\d+ms has been exceeded while executing GET /jsonslow for server.*"));
    }

    @Test
    void unknownBodyTransformationFailsBeforeSending() {
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
                        "TimeoutSeconds": 1,
                        "Authentication": {
                          "ConnectionArn": "%s"
                        },
                        "Transform": {
                          "RequestBodyEncoding": "INVALID",
                          "RequestEncodingOptions": {
                            "ArrayFormat": "COMMAS"
                          }
                        }
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(connectionArn), """
                {
                  "endpoint": "%s/form",
                  "method": "GET",
                  "customerId": "cust-123"
                }
                """.formatted(baseUrl));

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.TaskFailed", execution.getError());
        assertThat(execution.getCause(), startsWith("Unsupported body transformer: INVALID"));
        assertEquals(0, receivedRequests.size());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "INDICES | colors%5B0%5D=red%20blue&colors%5B1%5D=a%26b&colors%5B2%5D=caf%C3%A9",
            "REPEAT | colors=red%20blue&colors=a%26b&colors=caf%C3%A9",
            "COMMAS | colors=red%20blue,a%26b,caf%C3%A9",
            "BRACKETS | colors%5B%5D=red%20blue&colors%5B%5D=a%26b&colors%5B%5D=caf%C3%A9"
    })
    void formTransformationSendsTheDocumentedArrayFormats(String format, String expected) {
        Execution execution = runForm("{\"colors\":[\"red blue\",\"a&b\",\"café\"]}",
                "{\"ArrayFormat\":\"" + format + "\"}");

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        RecordedRequest request = onlyRequest();
        assertEquals("POST", request.method());
        assertEquals("application/x-www-form-urlencoded", request.firstHeader("Content-Type"));
        assertEquals(expected, request.body());
    }

    @Test
    void formTransformationDefaultsToIndicesAndEncodesNestedObjectsAndScalars() {
        Execution execution = runForm("""
                {"customer":"cust-123","metadata":{"order_details":"monthly report"},
                 "symbols":"a&b=c+%","enabled":true,"count":3,"empty":"","nothing":null,
                 "items":["one","two"]}
                """, "{}");

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertEquals("customer=cust-123&metadata%5Border_details%5D=monthly%20report"
                + "&symbols=a%26b%3Dc%2B%25&enabled=true&count=3&empty=&nothing="
                + "&items%5B0%5D=one&items%5B1%5D=two", onlyRequest().body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"ArrayFormat\":\"INVALID\"}", "{\"ArrayFormat\":null}",
            "{\"ArrayFormat\":{}}", "[]"})
    void malformedFormOptionsFailBeforeSending(String options) {
        Execution execution = runForm("{\"value\":1}", options);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.TaskFailed", execution.getError());
        assertEquals(0, receivedRequests.size());
    }

    @Test
    void basicConnectionAuthorizesTheRequest() {
        Execution execution = run(getJsonDefinition(connectionArn), "{}");

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8)),
                onlyRequest().firstHeader("Authorization"));
    }

    @Test
    void connectionParametersAreMergedOverTheTasksAndWinOnDuplicateKeys() throws Exception {
        // The data merging example of the AWS HTTP Task guide, with its API_KEY connection.
        String apiKeyConnection = createConnection("API_KEY", """
                {"ApiKeyAuthParameters":{"ApiKeyName":"ApiKey","ApiKeyValue":"key_value"},
                 "InvocationHttpParameters":{
                   "BodyParameters":[{"Key":"BodyParam","Value":"connection_body_param"}],
                   "HeaderParameters":[{"Key":"Header-Param","Value":"connection_header_param"}],
                   "QueryStringParameters":[{"Key":"QueryParam","Value":"connection_query_param"}]}}
                """);

        Execution execution = run("""
                {"StartAt":"CallHttp","States":{"CallHttp":{"Type":"Task",
                 "Resource":"arn:aws:states:::http:invoke","Parameters":{
                   "ApiEndpoint":"%s/text","Method":"POST",
                   "Authentication":{"ConnectionArn":"%s"},
                   "Headers":{"Request-Id":"my_request_id","header-param":"state_machine_header_param"},
                   "RequestBody":{"Job":"Software Engineer","BodyParam":"state_machine_body_param"},
                   "QueryParameters":{"QueryParam":"state_machine_query_param"}
                 },"End":true}}}
                """.formatted(baseUrl, apiKeyConnection), "{}");

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        RecordedRequest request = onlyRequest();
        assertEquals("/text?QueryParam=connection_query_param", request.path());
        assertEquals(List.of("key_value"), request.headerValues("ApiKey"));
        assertEquals(List.of("connection_header_param"), request.headerValues("Header-Param"));
        assertEquals(List.of("my_request_id"), request.headerValues("Request-Id"));
        assertEquals(objectMapper.readTree("""
                {"Job":"Software Engineer","BodyParam":"connection_body_param"}
                """), objectMapper.readTree(request.body()));
    }

    @Test
    void connectionBodyParametersAreMergedBeforeTheBodyIsUrlEncoded() {
        connectionArn = createConnection("BASIC", basicAuth("""
                ,"InvocationHttpParameters":{"BodyParameters":[{"Key":"mode","Value":"live"}]}"""));

        Execution execution = runForm("{\"items\":[\"a\",\"b\"]}", "{\"ArrayFormat\":\"REPEAT\"}");

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertEquals("items=a&items=b&mode=live", onlyRequest().body());
    }

    @Test
    void stringRequestBodyCannotTakeConnectionBodyParameters() {
        String bodyConnection = createConnection("BASIC", basicAuth("""
                ,"InvocationHttpParameters":{"BodyParameters":[{"Key":"mode","Value":"live"}]}"""));

        Execution execution = run("""
                {"StartAt":"CallHttp","States":{"CallHttp":{"Type":"Task",
                 "Resource":"arn:aws:states:::http:invoke","Parameters":{
                   "ApiEndpoint":"%s/text","Method":"POST",
                   "InvocationConfig":{"ConnectionArn":"%s"},
                   "RequestBody":"plain text"
                 },"End":true}}}
                """.formatted(baseUrl, bodyConnection), "{}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals(0, receivedRequests.size());
    }

    @Test
    void connectionQueryParameterReplacesOnlyTheSameCaseSensitiveName() {
        String queryConnection = createConnection("BASIC", basicAuth("""
                ,"InvocationHttpParameters":{"QueryStringParameters":[{"Key":"foo","Value":"connection"}]}"""));

        Execution execution = run("""
                {"StartAt":"CallHttp","States":{"CallHttp":{"Type":"Task",
                 "Resource":"arn:aws:states:::http:invoke","Parameters":{
                   "ApiEndpoint":"%s/json","Method":"GET",
                   "InvocationConfig":{"ConnectionArn":"%s"},
                   "QueryParameters":{"Foo":"task"}
                 },"End":true}}}
                """.formatted(baseUrl, queryConnection), "{}");

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertEquals("/json?Foo=task&foo=connection", onlyRequest().path());
    }

    @Test
    void oauthTokenExchangeSpendsTheTasksTimeout() {
        String oauthConnection = createConnection("OAUTH_CLIENT_CREDENTIALS", """
                {"OAuthParameters":{"AuthorizationEndpoint":"%s/token-slow","HttpMethod":"POST",
                 "ClientParameters":{"ClientID":"cid","ClientSecret":"csecret"}}}
                """.formatted(baseUrl));

        Execution execution = run("""
                {"StartAt":"CallHttp","States":{"CallHttp":{"Type":"Task",
                 "Resource":"arn:aws:states:::http:invoke","Parameters":{
                   "ApiEndpoint":"%s/json","Method":"GET","TimeoutSeconds":1,
                   "InvocationConfig":{"ConnectionArn":"%s"}
                 },"End":true}}}
                """.formatted(baseUrl, oauthConnection), "{}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Http.Socket", execution.getError());
        assertEquals(0, receivedRequests.size());
    }

    @Test
    void missingConnectionFailsWithConnectionResourceNotFound() {
        Execution execution = run(getJsonDefinition(MISSING_CONNECTION_ARN), "{}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Events.ConnectionResource.ResourceNotFound", execution.getError());
        assertEquals(0, receivedRequests.size());
    }

    private String getJsonDefinition(String connection) {
        return """
                {"StartAt":"CallHttp","States":{"CallHttp":{"Type":"Task",
                 "Resource":"arn:aws:states:::http:invoke","Parameters":{
                   "ApiEndpoint":"%s/json","Method":"GET",
                   "InvocationConfig":{"ConnectionArn":"%s"}
                 },"End":true}}}
                """.formatted(baseUrl, connection);
    }

    private String createConnection(String authorizationType, String authParameters) {
        String name = "http-task-" + UUID.randomUUID();
        connectionNames.add(name);
        return eventBridgeService.createConnection(name, null, authorizationType, authParameters, null, null, REGION)
                .getConnectionArn();
    }

    private static String basicAuth(String moreAuthParameters) {
        return "{\"BasicAuthParameters\":{\"Username\":\"user\",\"Password\":\"secret\"}" + moreAuthParameters + "}";
    }

    private Execution runForm(String body, String options) {
        return run("""
                {"StartAt":"CallHttp","States":{"CallHttp":{"Type":"Task",
                 "Resource":"arn:aws:states:::http:invoke","Parameters":{
                   "ApiEndpoint":"%s/text","Method":"POST",
                   "Authentication":{"ConnectionArn":"%s"},
                   "Headers":{"Content-Type":"application/x-www-form-urlencoded"},
                   "RequestBody.$":"$.body",
                   "Transform":{"RequestBodyEncoding":"URL_ENCODED","RequestEncodingOptions":%s}
                 },"End":true}}}
                """.formatted(baseUrl, connectionArn, options), "{\"body\":" + body + "}");
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
        return receivedRequests.getFirst();
    }

    // TODO Can use Hamcrest
    private void assertQueryContains(String query, String expected) {
        assertNotNull(query);
        assertTrue(query.contains(expected), "expected query to contain " + expected + " but was " + query);
    }
}

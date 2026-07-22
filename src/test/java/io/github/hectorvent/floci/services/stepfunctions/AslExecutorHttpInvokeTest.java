package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
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
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.MultiMap;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.core.http.HttpServer;
import io.vertx.mutiny.ext.web.Router;
import io.vertx.mutiny.ext.web.RoutingContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
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

    @Inject
    EmulatorConfig emulatorConfig;

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

        var router = Router.router(vertx);
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
            .listenAndAwait(0);
        baseUrl = "http://127.0.0.1:" + server.actualPort();

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
            emulatorConfig,
            vertx);
    }

    private void recordRequest(final RoutingContext ctx, String body) {
        var request = ctx.request();
        var requestRecord = new RecordedRequest(
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
        assertTrue(output.path("Headers").has("Content-Type"));

        RecordedRequest request = onlyRequest();
        assertEquals("GET", request.method());
        assertEquals("/json?q=hello&tag=blue&tag=green", request.path());
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
                """.formatted(CONNECTION_ARN), """
                {
                  "endpoint": "%s/jsonslow",
                  "method": "GET",
                  "customerId": "cust-123"
                }
                """.formatted(baseUrl));

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Http.Socket", execution.getError());
        assertThat(execution.getCause(), startsWith("The timeout period of 1000ms has been exceeded while executing GET /jsonslow for server"));
    }

    @Test
    void formTransformationIsUnsupported() {
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
                          "RequestBodyEncoding": "URL_ENCODED",
                          "RequestEncodingOptions": {
                            "ArrayFormat": "COMMAS"
                          }
                        }
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(CONNECTION_ARN), """
                {
                  "endpoint": "%s/form",
                  "method": "GET",
                  "customerId": "cust-123"
                }
                """.formatted(baseUrl));

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.TaskFailed", execution.getError());
        assertThat(execution.getCause(), startsWith("URL-encoded request bodies are not supported yet"));
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

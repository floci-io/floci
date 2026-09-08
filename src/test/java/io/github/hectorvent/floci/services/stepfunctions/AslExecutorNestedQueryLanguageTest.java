package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Pins the query language a state inside a Map's {@code ItemProcessor} runs under.
 *
 * <p>The Amazon States Language specification resolves it in exactly two levels: "If a Map or
 * Parallel State has a <code>QueryLanguage</code> field, the default query language for each state
 * inside the Map's <code>ItemProcessor</code> or the Parallel's <code>Branches</code> fields is the
 * state machine's query language, and is independent of the Map or Parallel State's query
 * language." (<a href="https://states-language.net/spec.html">states-language.net/spec.html</a>)
 *
 * <p>Each machine below carries both halves in one execution: the Map's own {@code Items} field
 * uses the Map's declared language, while the bare {@code Pass} inside its {@code ItemProcessor}
 * applies JSONPath {@code .$} syntax, the state machine's. The outputs are the literals the real
 * service returned for the same definitions.
 */
@QuarkusTest
class AslExecutorNestedQueryLanguageTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";
    private static final String ITEMS_INPUT = "{\"items\":[{\"n\":1},{\"n\":2}]}";
    private static final String DOUBLED_ITEMS = "[{\"doubled\":1},{\"doubled\":2}]";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AslExecutor executor;

    @Inject
    Vertx vertx;

    @BeforeEach
    void setUp() {
        executor = new AslExecutor(
                mock(LambdaExecutorService.class),
                mock(LambdaFunctionStore.class),
                mock(DynamoDbService.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler.class),
                mock(io.github.hectorvent.floci.services.ec2.Ec2Service.class),
                mock(S3Service.class),
                mock(io.github.hectorvent.floci.services.ecs.EcsService.class),
                mock(io.github.hectorvent.floci.services.ecs.EcsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerService.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerController.class),
                objectMapper,
                new JsonataEvaluator(objectMapper),
                mock(Instance.class), mock(EmulatorConfig.class), vertx, null);
    }

    /**
     * A JSONata Map over a bare ItemProcessor Pass: the Map evaluates its own {% %} expression and
     * the Pass still resolves {@code .$}, which exists only in JSONPath, the state machine's.
     */
    @Test
    @Timeout(60)
    void jsonataMapEvaluatesItsItemsWhileItsItemProcessorPassStaysOnJsonPath() {
        Execution execution = run("""
                {
                  "StartAt": "ItemsMap",
                  "States": {
                    "ItemsMap": {
                      "Type": "Map",
                      "QueryLanguage": "JSONata",
                      "Items": "{% $states.input.items %}",
                      "ItemProcessor": {
                        "StartAt": "HelloWorld",
                        "States": {
                          "HelloWorld": {
                            "Type": "Pass",
                            "Parameters": {"doubled.$": "$.n"},
                            "End": true
                          }
                        }
                      },
                      "End": true
                    }
                  }
                }
                """);

        assertEquals("SUCCEEDED", execution.getStatus());
        assertOutputIs(DOUBLED_ITEMS, execution);
    }

    /**
     * The same machine with the Map declaring nothing, so the Map's own field is the JSONPath
     * ItemsPath. The inner Pass is untouched by the difference and produces the same output: the
     * Map's declaration reaches its own fields and nothing else.
     */
    @Test
    @Timeout(60)
    void mapDeclaringNothingLeavesTheSameItemProcessorOutput() {
        Execution execution = run("""
                {
                  "StartAt": "ItemsMap",
                  "States": {
                    "ItemsMap": {
                      "Type": "Map",
                      "ItemsPath": "$.items",
                      "ItemProcessor": {
                        "StartAt": "HelloWorld",
                        "States": {
                          "HelloWorld": {
                            "Type": "Pass",
                            "Parameters": {"doubled.$": "$.n"},
                            "End": true
                          }
                        }
                      },
                      "End": true
                    }
                  }
                }
                """);

        assertEquals("SUCCEEDED", execution.getStatus());
        assertOutputIs(DOUBLED_ITEMS, execution);
    }

    /** Two JSONata Maps deep, the innermost bare Pass still applies JSONPath syntax. */
    @Test
    @Timeout(60)
    void innermostPassStaysOnJsonPathUnderTwoJsonataMaps() {
        Execution execution = run("""
                {
                  "StartAt": "ItemsMap",
                  "States": {
                    "ItemsMap": {
                      "Type": "Map",
                      "QueryLanguage": "JSONata",
                      "Items": "{% $states.input.items %}",
                      "ItemProcessor": {
                        "StartAt": "SubItemsMap",
                        "States": {
                          "SubItemsMap": {
                            "Type": "Map",
                            "QueryLanguage": "JSONata",
                            "Items": "{% $states.input.subitems %}",
                            "ItemProcessor": {
                              "StartAt": "HelloWorld",
                              "States": {
                                "HelloWorld": {
                                  "Type": "Pass",
                                  "Parameters": {"doubled.$": "$.n"},
                                  "End": true
                                }
                              }
                            },
                            "End": true
                          }
                        }
                      },
                      "End": true
                    }
                  }
                }
                """, "{\"items\":[{\"subitems\":[{\"n\":1}]},{\"subitems\":[{\"n\":2}]}]}");

        assertEquals("SUCCEEDED", execution.getStatus());
        assertOutputIs("[[{\"doubled\":1}],[{\"doubled\":2}]]", execution);
    }

    private void assertOutputIs(String expectedJson, Execution execution) {
        try {
            assertEquals(objectMapper.readTree(expectedJson),
                    objectMapper.readTree(execution.getOutput()),
                    "cause: " + execution.getCause());
        } catch (JsonProcessingException e) {
            throw new AssertionError("execution output is not JSON: " + execution.getOutput()
                    + ", cause: " + execution.getCause(), e);
        }
    }

    private Execution run(String definition) {
        return run(definition, ITEMS_INPUT);
    }

    private Execution run(String definition, String input) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("nested-query-language-test");
        stateMachine.setStateMachineArn(
                "arn:aws:states:%s:%s:stateMachine:nested-query-language-test"
                        .formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("nested-query-language-execution");
        execution.setExecutionArn(("arn:aws:states:%s:%s:execution:nested-query-language-test"
                + ":nested-query-language-execution").formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);
        execution.setStatus("RUNNING");

        List<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }
}

package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Guards that an {@link Error} escaping a state ends the execution instead of leaving it RUNNING
 * forever, the failure reported in issue #2666.
 *
 * <p>The trigger described there is native-image only: the JSONata {@code ??} and {@code ?:}
 * operators clone their parser through Java serialization, which a GraalVM image answers with an
 * {@code UnsupportedFeatureError}. The defect underneath it is not native-specific, so these tests
 * inject the same shape on the JVM: a {@link JsonataEvaluator} whose template resolution throws a
 * {@link LinkageError}, which is what evaluating {@code Output} does inside the image.
 *
 * <p>Every case is bounded by a timeout, because the symptom being guarded is an execution that
 * never terminates.
 */
@QuarkusTest
class AslExecutorErrorTerminationTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";
    private static final String ERROR_MESSAGE = "parser clone is unavailable in this image";
    private static final String EXPECTED_CAUSE = "java.lang.LinkageError: " + ERROR_MESSAGE;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AslExecutor executor;

    @Inject
    Vertx vertx;

    /** Throws where the native image throws: resolving a state's JSONata {@code Output}. */
    private static final class CrashingJsonataEvaluator extends JsonataEvaluator {
        CrashingJsonataEvaluator(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        JsonNode resolveTemplate(JsonNode template, String field, JsonNode statesVar, JsonNode variables) {
            throw new LinkageError(ERROR_MESSAGE);
        }
    }

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
                new CrashingJsonataEvaluator(objectMapper),
                mock(Instance.class), mock(EmulatorConfig.class), vertx);
    }

    private static final String PASS_WITH_JSONATA_OUTPUT = """
            {
              "QueryLanguage": "JSONata",
              "StartAt": "Crash",
              "States": {
                "Crash": {
                  "Type": "Pass",
                  "Output": {"v": "{% 42 %}"},
                  "End": true
                }
              }
            }
            """;

    @Test
    @Timeout(60)
    void errorInsideAStateFailsTheExecutionWithTheErrorAsCause() {
        List<HistoryEvent> history = new ArrayList<>();
        Execution execution = runSync(PASS_WITH_JSONATA_OUTPUT, history);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals(EXPECTED_CAUSE, execution.getCause());
        assertNotNull(execution.getStopDate());
    }

    @Test
    @Timeout(60)
    void errorInsideAStateRecordsAnExecutionFailedEvent() {
        List<HistoryEvent> history = new ArrayList<>();
        runSync(PASS_WITH_JSONATA_OUTPUT, history);

        HistoryEvent failed = history.stream()
                .filter(event -> "ExecutionFailed".equals(event.getType()))
                .findFirst()
                .orElse(null);
        assertNotNull(failed, "history has no ExecutionFailed event: " + typesOf(history));
        assertEquals("States.Runtime", failed.getDetails().get("error"));
        assertEquals(EXPECTED_CAUSE, failed.getDetails().get("cause"));
    }

    @Test
    @Timeout(60)
    void errorInsideAStatePublishesTheTerminalUpdateToTheListener() throws Exception {
        StateMachine stateMachine = stateMachine(PASS_WITH_JSONATA_OUTPUT);
        Execution execution = startedExecution(stateMachine);
        List<HistoryEvent> history = new ArrayList<>();
        CountDownLatch published = new CountDownLatch(1);

        executor.executeAsync(stateMachine, execution, history,
                (updated, events) -> published.countDown());

        assertTrue(published.await(30, TimeUnit.SECONDS),
                "no terminal update was published; execution is still " + execution.getStatus());
        assertEquals("FAILED", execution.getStatus());
        assertEquals(EXPECTED_CAUSE, execution.getCause());
    }

    @Test
    @Timeout(60)
    void errorInsideAParallelBranchFailsTheExecutionWithTheSameCause() {
        Execution execution = runSync("""
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Fan",
                  "States": {
                    "Fan": {
                      "Type": "Parallel",
                      "Branches": [{
                        "StartAt": "Crash",
                        "States": {
                          "Crash": {"Type": "Pass", "Output": {"v": "{% 42 %}"}, "End": true}
                        }
                      }],
                      "End": true
                    }
                  }
                }
                """, new ArrayList<>());

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals(EXPECTED_CAUSE, execution.getCause());
    }

    @Test
    @Timeout(60)
    void errorInsideASerialMapIterationFailsTheExecution() {
        Execution execution = runSync(mapOverItems(1), new ArrayList<>());

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals(EXPECTED_CAUSE, execution.getCause());
    }

    @Test
    @Timeout(60)
    void errorInsideAConcurrentMapIterationFailsTheExecution() {
        Execution execution = runSync(mapOverItems(4), new ArrayList<>());

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals(EXPECTED_CAUSE, execution.getCause());
    }

    /**
     * {@code maxConcurrency} 1 keeps the iterations on the calling thread; anything above it hands
     * them to {@code MapIterationScheduler}'s worker pool. Both routes have to terminate.
     */
    private static String mapOverItems(int maxConcurrency) {
        return """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Each",
                  "States": {
                    "Each": {
                      "Type": "Map",
                      "Items": "{% [1, 2, 3, 4] %}",
                      "MaxConcurrency": __MAX_CONCURRENCY__,
                      "ItemProcessor": {
                        "StartAt": "Crash",
                        "States": {
                          "Crash": {"Type": "Pass", "Output": {"v": "{% 42 %}"}, "End": true}
                        }
                      },
                      "End": true
                    }
                  }
                }
                """.replace("__MAX_CONCURRENCY__", String.valueOf(maxConcurrency));
    }

    private static String typesOf(List<HistoryEvent> history) {
        return history.stream().map(HistoryEvent::getType).toList().toString();
    }

    private Execution runSync(String definition, List<HistoryEvent> history) {
        StateMachine stateMachine = stateMachine(definition);
        Execution execution = startedExecution(stateMachine);
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }

    private static StateMachine stateMachine(String definition) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("error-termination-test");
        stateMachine.setStateMachineArn(
                "arn:aws:states:%s:%s:stateMachine:error-termination-test".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);
        return stateMachine;
    }

    /** Mirrors {@code StepFunctionsService#startExecution}: the execution is RUNNING when it starts. */
    private static Execution startedExecution(StateMachine stateMachine) {
        Execution execution = new Execution();
        execution.setName("error-termination-execution");
        execution.setExecutionArn(
                "arn:aws:states:%s:%s:execution:error-termination-test:error-termination-execution"
                        .formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{}");
        execution.setStatus("RUNNING");
        return execution;
    }
}

package io.github.hectorvent.floci.services.stepfunctions;

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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * Guards the terminal metadata written on the outer catch in {@code doExecute}, the path that
 * previously set only the status and left {@code error} and {@code cause} null permanently on an
 * execution DescribeExecution reports as FAILED.
 *
 * <p>A missing state reaches it deterministically: the "State not found" throw sits outside the
 * per-state try, so it unwinds the whole execution loop rather than being caught as a state
 * failure. The field ordering this class does not assert is untestable from here, since the window
 * between two adjacent setters is a few instructions wide.
 */
@QuarkusTest
class AslExecutorTerminalStateTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";

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
                mock(Instance.class), mock(EmulatorConfig.class), vertx);
    }

    @Test
    void executionFailedOnTheOuterCatchCarriesErrorAndCause() {
        Execution execution = run("""
                {
                  "StartAt": "NoSuchState",
                  "States": {
                    "Unreachable": {
                      "Type": "Succeed"
                    }
                  }
                }
                """);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals("State not found: NoSuchState", execution.getCause());
        assertNotNull(execution.getStopDate());
    }

    @Test
    void executionFailedOnAnUnresolvableNextCarriesErrorAndCause() {
        Execution execution = run("""
                {
                  "StartAt": "First",
                  "States": {
                    "First": {
                      "Type": "Pass",
                      "Next": "Vanished"
                    }
                  }
                }
                """);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals("State not found: Vanished", execution.getCause());
        assertNotNull(execution.getStopDate());
    }

    @Test
    void succeededExecutionCarriesItsOutputAndStopDate() {
        Execution execution = run("""
                {
                  "StartAt": "Only",
                  "States": {
                    "Only": {
                      "Type": "Pass",
                      "End": true
                    }
                  }
                }
                """);

        assertEquals("SUCCEEDED", execution.getStatus());
        assertNotNull(execution.getOutput());
        assertNotNull(execution.getStopDate());
    }

    private Execution run(String definition) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("terminal-state-test");
        stateMachine.setStateMachineArn(
                "arn:aws:states:%s:%s:stateMachine:terminal-state-test".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("terminal-state-execution");
        execution.setExecutionArn(
                "arn:aws:states:%s:%s:execution:terminal-state-test:terminal-state-execution"
                        .formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{}");

        List<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }
}

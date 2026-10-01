package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.scheduler.SchedulerController;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.sns.SnsJsonHandler;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@code states:startExecution.sync} or {@code .sync:2} Task whose child ends other than
 * SUCCEEDED fails with {@code States.TaskFailed}, whatever the child's own error, and carries the
 * child's DescribeExecution response as its cause. Measured on AWS (ap-northeast-1, 2026-09-29) for
 * six endings under both modes: FAILED with a custom error with and without a cause, FAILED with
 * {@code States.Timeout} from a Task's own TimeoutSeconds, FAILED with {@code States.TaskFailed},
 * TIMED_OUT, and ABORTED by a StopExecution carrying an error and a cause.
 */
class AslExecutorNestedSyncFailureTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";
    private static final String CHILD_SM_ARN =
            "arn:aws:states:%s:%s:stateMachine:child".formatted(REGION, ACCOUNT);
    private static final String CHILD_ARN =
            "arn:aws:states:%s:%s:execution:child:run-1".formatted(REGION, ACCOUNT);
    private static final String PARENT_ARN =
            "arn:aws:states:%s:%s:execution:parent:parent-run".formatted(REGION, ACCOUNT);
    private static final String CHILD_TAIL = """
            "ExecutionArn":"%s","Input":"{\\"k\\":1}","InputDetails":{"Included":true},"Name":"run-1",\
            "RedriveCount":0,"RedriveStatus":"REDRIVABLE","StartDate":1790676767025,\
            "StateMachineArn":"%s",""".formatted(CHILD_ARN, CHILD_SM_ARN);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StepFunctionsService sfnService;
    private List<HistoryEvent> history;

    @BeforeEach
    void setUp() {
        sfnService = mock(StepFunctionsService.class);
        when(sfnService.startExecution(any(), any(), any(), any())).thenReturn(child("RUNNING", null, null));
        Execution parent = new Execution();
        parent.setExecutionArn(PARENT_ARN);
        parent.setStatus("RUNNING");
        when(sfnService.describeExecution(PARENT_ARN)).thenReturn(parent);
    }

    static Stream<Arguments> childEndings() {
        List<Arguments> endings = List.of(
                Arguments.of("FAILED", "Boom", "custom cause",
                        "{\"Cause\":\"custom cause\",\"Error\":\"Boom\"," + CHILD_TAIL
                                + "\"Status\":\"FAILED\",\"StopDate\":1790676767071}"),
                Arguments.of("FAILED", "Boom", null,
                        "{\"Error\":\"Boom\"," + CHILD_TAIL + "\"Status\":\"FAILED\",\"StopDate\":1790676767071}"),
                Arguments.of("FAILED", "States.Timeout", null,
                        "{\"Error\":\"States.Timeout\"," + CHILD_TAIL
                                + "\"Status\":\"FAILED\",\"StopDate\":1790676767071}"),
                Arguments.of("FAILED", "States.TaskFailed", "child says TaskFailed",
                        "{\"Cause\":\"child says TaskFailed\",\"Error\":\"States.TaskFailed\"," + CHILD_TAIL
                                + "\"Status\":\"FAILED\",\"StopDate\":1790676767071}"),
                Arguments.of("TIMED_OUT", null, null,
                        "{" + CHILD_TAIL + "\"Status\":\"TIMED_OUT\",\"StopDate\":1790676767071}"),
                Arguments.of("ABORTED", "ManualStop", "stopped by test",
                        "{\"Cause\":\"stopped by test\",\"Error\":\"ManualStop\"," + CHILD_TAIL
                                + "\"Status\":\"ABORTED\",\"StopDate\":1790676767071}"));
        return Stream.of(".sync", ".sync:2").flatMap(mode -> endings.stream().map(ending -> {
            Object[] values = ending.get();
            return Arguments.of(mode, values[0], values[1], values[2], values[3]);
        }));
    }

    @ParameterizedTest(name = "{0} child {1} {2}")
    @MethodSource("childEndings")
    void aChildThatDoesNotSucceedFailsTheTaskWithStatesTaskFailedAndItsDescription(
            String mode, String status, String error, String cause, String expectedCause) {
        when(sfnService.describeExecution(CHILD_ARN)).thenReturn(child(status, error, cause));

        Execution execution = run(mode, "");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.TaskFailed", execution.getError());
        assertEquals(expectedCause, execution.getCause());
        HistoryEvent taskFailed = history.stream().filter(event -> "TaskFailed".equals(event.getType()))
                .findFirst().orElseThrow(() -> new AssertionError("no TaskFailed in " + history));
        assertEquals("States.TaskFailed", taskFailed.getDetails().get("error"));
        assertEquals(expectedCause, taskFailed.getDetails().get("cause"));
    }

    @Test
    void aChildStartedThroughAnAliasCarriesTheAliasAndVersionInTheirAlphabeticalPlaces() {
        Execution failed = child("FAILED", "Boom", null);
        failed.setStateMachineAliasArn(CHILD_SM_ARN + ":live");
        failed.setStateMachineVersionArn(CHILD_SM_ARN + ":1");
        when(sfnService.describeExecution(CHILD_ARN)).thenReturn(failed);

        Execution execution = run(".sync:2", "");

        // Measured on AWS: an alias carries both ARNs, between StartDate and Status.
        assertEquals("{\"Error\":\"Boom\"," + CHILD_TAIL.replace(
                        "\"StateMachineArn\":", "\"StateMachineAliasArn\":\"" + CHILD_SM_ARN + ":live\",\"StateMachineArn\":")
                        + "\"StateMachineVersionArn\":\"" + CHILD_SM_ARN + ":1\","
                        + "\"Status\":\"FAILED\",\"StopDate\":1790676767071}",
                execution.getCause());
    }

    @Test
    void aChildStartedThroughAVersionCarriesTheVersionOnly() {
        Execution failed = child("FAILED", "Boom", null);
        failed.setStateMachineVersionArn(CHILD_SM_ARN + ":1");
        when(sfnService.describeExecution(CHILD_ARN)).thenReturn(failed);

        Execution execution = run(".sync", "");

        assertEquals("{\"Error\":\"Boom\"," + CHILD_TAIL
                        + "\"StateMachineVersionArn\":\"" + CHILD_SM_ARN + ":1\","
                        + "\"Status\":\"FAILED\",\"StopDate\":1790676767071}",
                execution.getCause());
    }

    @Test
    void aTerminalStatusIsReadTogetherWithTheFieldsWrittenBeforeItsMonitorIsReleased() throws Exception {
        // The child's worker writes its terminal fields under the execution's monitor. This writer
        // publishes the status first and holds the monitor for 200 ms before writing the rest, and
        // the parent's first read of the child lands inside that window, so a reader that skipped
        // the monitor would build the cause from a half-written child.
        Execution live = child("RUNNING", null, null);
        CountDownLatch statusWritten = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            synchronized (live) {
                live.setStatus("FAILED");
                statusWritten.countDown();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                live.setError("Boom");
                live.setCause("custom cause");
                live.setStopDate(1790676767.071);
            }
        });
        when(sfnService.describeExecution(CHILD_ARN)).thenAnswer(invocation -> {
            if (writer.getState() == Thread.State.NEW) {
                writer.start();
                statusWritten.await();
            }
            return live;
        });

        Execution execution = run(".sync", "");
        writer.join();

        assertEquals("{\"Cause\":\"custom cause\",\"Error\":\"Boom\"," + CHILD_TAIL
                + "\"Status\":\"FAILED\",\"StopDate\":1790676767071}", execution.getCause());
    }

    @Test
    void aCatchOnTheChildsOwnErrorDoesNotFireButOneOnStatesTaskFailedDoes() throws Exception {
        when(sfnService.describeExecution(CHILD_ARN)).thenReturn(child("FAILED", "Boom", "custom cause"));

        Execution execution = run(".sync:2", """
                "Catch": [
                  {"ErrorEquals": ["Boom"], "Next": "CaughtByName"},
                  {"ErrorEquals": ["States.TaskFailed"], "Next": "CaughtAsTaskFailed"}
                ],""");

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertEquals("CaughtAsTaskFailed", objectMapper.readTree(execution.getOutput()).path("caughtBy").asText());
    }

    @Test
    void aChildThatTimedOutIsNotCaughtUnderStatesTimeout() throws Exception {
        when(sfnService.describeExecution(CHILD_ARN)).thenReturn(child("FAILED", "States.Timeout", null));

        Execution execution = run(".sync", """
                "Catch": [
                  {"ErrorEquals": ["States.Timeout"], "Next": "CaughtByName"},
                  {"ErrorEquals": ["States.ALL"], "Next": "CaughtAsTaskFailed"}
                ],""");

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertEquals("CaughtAsTaskFailed", objectMapper.readTree(execution.getOutput()).path("caughtBy").asText());
    }

    private static Execution child(String status, String error, String cause) {
        Execution current = new Execution();
        current.setExecutionArn(CHILD_ARN);
        current.setStateMachineArn(CHILD_SM_ARN);
        current.setName("run-1");
        current.setInput("{\"k\":1}");
        current.setStatus(status);
        current.setError(error);
        current.setCause(cause);
        current.setStartDate(1790676767.025);
        if (!"RUNNING".equals(status)) {
            current.setStopDate(1790676767.071);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private AslExecutor newExecutor() {
        Instance<StepFunctionsService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(sfnService);
        return new AslExecutor(
                mock(LambdaExecutorService.class),
                mock(LambdaFunctionStore.class),
                mock(DynamoDbFacade.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class), mock(SnsJsonHandler.class),
                mock(CloudFormationQueryHandler.class),
                mock(Ec2Service.class),
                mock(S3Service.class),
                mock(EcsService.class),
                mock(EcsJsonHandler.class),
                mock(EventBridgeHandler.class),
                mock(SchedulerService.class),
                mock(SchedulerController.class),
                objectMapper,
                new JsonataEvaluator(objectMapper),
                instance,
                mock(EmulatorConfig.class),
                null,
                null,
                Clock.systemUTC(),
                nanos -> { },
                null);
    }

    private Execution run(String mode, String catcher) {
        String definition = """
                {
                  "StartAt": "Child",
                  "States": {
                    "Child": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::states:startExecution%s",
                      %s
                      "Parameters": { "StateMachineArn": "%s", "Input": {"k": 1} },
                      "End": true
                    },
                    "CaughtByName": { "Type": "Pass", "Result": {"caughtBy": "CaughtByName"}, "End": true },
                    "CaughtAsTaskFailed": { "Type": "Pass", "Result": {"caughtBy": "CaughtAsTaskFailed"}, "End": true }
                  }
                }
                """.formatted(mode, catcher, CHILD_SM_ARN);

        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("parent");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:parent".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("parent-run");
        execution.setExecutionArn(PARENT_ARN);
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{}");

        history = new ArrayList<>();
        newExecutor().executeSync(stateMachine, execution, history, (updated, events) -> { });
        return execution;
    }
}

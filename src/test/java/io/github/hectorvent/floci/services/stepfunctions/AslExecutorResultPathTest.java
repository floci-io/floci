package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * End-to-end coverage for the ResultPath fix: a non-applicable ResultPath fails the execution with
 * {@code States.ResultPathMatchFailure} (was a silent input discard), the error is catchable via a
 * Parallel state's {@code Catch}, and object inputs merge unchanged.
 *
 * <p>CI-only: constructing {@link AslExecutor} pulls in Vert.x (absent in the offline sandbox). The merge
 * logic is covered locally by {@link ResultPathMergeTest}.
 */
class AslExecutorResultPathTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AslExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        executor = new AslExecutor(
                mock(LambdaExecutorService.class), mock(LambdaFunctionStore.class),
                mock(DynamoDbService.class), mock(DynamoDbJsonHandler.class), mock(SqsJsonHandler.class),
                mock(CloudFormationQueryHandler.class), mock(Ec2Service.class), mock(S3Service.class),
                mock(EcsService.class), mock(EcsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerService.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerController.class),
                mapper, new JsonataEvaluator(mapper),
                mock(Instance.class), mock(EmulatorConfig.class), null, null);
    }

    private Execution run(String definition, String input) {
        StateMachine sm = new StateMachine();
        sm.setName("rp");
        sm.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:rp");
        sm.setRoleArn("arn:aws:iam::000000000000:role/r");
        sm.setDefinition(definition);
        Execution exec = new Execution();
        exec.setName("rp-exec");
        exec.setExecutionArn("arn:aws:states:us-east-1:000000000000:execution:rp:e");
        exec.setStateMachineArn(sm.getStateMachineArn());
        exec.setInput(input);
        executor.executeSync(sm, exec, new ArrayList<HistoryEvent>(), (u, e) -> {
        });
        return exec;
    }

    @Test
    void nonObjectInputWithObjectResultPathFailsExecution() {
        Execution exec = run(
                "{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Pass\",\"ResultPath\":\"$.x\",\"End\":true}}}",
                "\"just-a-string\"");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.ResultPathMatchFailure", exec.getError());
    }

    @Test
    void objectInputMergesNormally() {
        Execution exec = run(
                "{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Pass\",\"Result\":{\"r\":1},"
                        + "\"ResultPath\":\"$.x\",\"End\":true}}}",
                "{\"k\":1}");
        assertEquals("SUCCEEDED", exec.getStatus());
    }

    @Test
    void resultPathMatchFailureIsUncaughtInParallelWithoutCatch() {
        Execution exec = run(
                "{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Parallel\","
                        + "\"Branches\":[{\"StartAt\":\"B\",\"States\":{\"B\":{\"Type\":\"Pass\",\"End\":true}}}],"
                        + "\"ResultPath\":\"$.x\",\"End\":true}}}",
                "\"just-a-string\"");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.ResultPathMatchFailure", exec.getError());
    }

    @Test
    void resultPathMatchFailureIsCatchableInParallel() {
        Execution exec = run(
                "{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Parallel\","
                        + "\"Branches\":[{\"StartAt\":\"B\",\"States\":{\"B\":{\"Type\":\"Pass\",\"End\":true}}}],"
                        + "\"ResultPath\":\"$.x\","
                        + "\"Catch\":[{\"ErrorEquals\":[\"States.ResultPathMatchFailure\"],\"Next\":\"Recover\"}],"
                        + "\"End\":true},"
                        + "\"Recover\":{\"Type\":\"Pass\",\"End\":true}}}",
                "\"just-a-string\"");
        assertEquals("SUCCEEDED", exec.getStatus());
    }

    @Test
    void catcherResultPathFailureReportsResultPathMatchFailureNotRuntime() {
        // A matched Catcher whose own ResultPath cannot apply to the (non-object) input must terminate
        // with States.ResultPathMatchFailure, not a relabeled States.Runtime from the outer handler.
        Execution exec = run(
                "{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Parallel\","
                        + "\"Branches\":[{\"StartAt\":\"F\",\"States\":{\"F\":{\"Type\":\"Fail\","
                        + "\"Error\":\"BranchErr\",\"Cause\":\"x\"}}}],"
                        + "\"Catch\":[{\"ErrorEquals\":[\"States.ALL\"],\"Next\":\"Recover\",\"ResultPath\":\"$.error\"}],"
                        + "\"End\":true},"
                        + "\"Recover\":{\"Type\":\"Pass\",\"End\":true}}}",
                "\"just-a-string\"");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.ResultPathMatchFailure", exec.getError());
    }
}

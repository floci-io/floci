package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sns.SnsJsonHandler;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end coverage for nested {@code states:startExecution}: the parent's resolved {@code Input} and
 * {@code Name} reach the child {@code StartExecution} correctly. Verifies the fix: a
 * {@code States.JsonToString} Input is passed as JSON text (child {@code $} becomes an object), a plain
 * string stays a string, {@code Name}/{@code Name.$} are honored (previously ignored), and a supplied
 * {@code Name} that does not resolve to a non-empty string fails rather than silently generating one.
 *
 * <p>CI-only: constructing {@link AslExecutor} pulls in Vert.x, unavailable in the offline sandbox. The
 * encoding/provenance logic itself is covered locally by {@link NestedExecutionInputTest}.
 */
class AslExecutorNestedStartExecutionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AslExecutor executor;
    private StepFunctionsService childSfn;
    private List<HistoryEvent> history;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        childSfn = mock(StepFunctionsService.class);
        Instance<StepFunctionsService> sfnInstance = mock(Instance.class);
        when(sfnInstance.get()).thenReturn(childSfn);
        Execution childExec = new Execution();
        childExec.setExecutionArn("arn:aws:states:us-east-1:000000000000:execution:child:e1");
        childExec.setStatus("RUNNING");
        childExec.setStartDate(1.0);
        when(childSfn.startExecution(any(), any(), any(), any())).thenReturn(childExec);

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().stepfunctions().maxWaitSeconds()).thenReturn(30);

        executor = new AslExecutor(
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
                mock(io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerService.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerController.class),
                mapper,
                new JsonataEvaluator(mapper),
                sfnInstance,
                config,
                null, null);
    }

    private Execution runParent(String parentDefinition, String input) {
        return runParent(parentDefinition, input, new ArrayList<>());
    }

    /** {@code recorded} is the history the execution starts with, counted towards the event limit. */
    private Execution runParent(String parentDefinition, String input, List<HistoryEvent> recorded) {
        StateMachine sm = new StateMachine();
        sm.setName("parent");
        sm.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:parent");
        sm.setRoleArn("arn:aws:iam::000000000000:role/r");
        sm.setDefinition(parentDefinition);
        Execution exec = new Execution();
        exec.setName("parent-exec");
        exec.setExecutionArn("arn:aws:states:us-east-1:000000000000:execution:parent:pe");
        exec.setStateMachineArn(sm.getStateMachineArn());
        exec.setInput(input);
        history = recorded;
        executor.executeSync(sm, exec, history, (u, e) -> {
        });
        return exec;
    }

    /** Returns {capturedName, capturedChildInput} from the single child startExecution call. */
    private String[] captureChildStart(String parentDefinition, String input) {
        runParent(parentDefinition, input);
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> childInput = ArgumentCaptor.forClass(String.class);
        verify(childSfn).startExecution(any(), name.capture(), childInput.capture(), any());
        return new String[]{name.getValue(), childInput.getValue()};
    }

    private static String parent(String parametersJson) {
        return "{\"StartAt\":\"Nest\",\"States\":{\"Nest\":{\"Type\":\"Task\","
                + "\"Resource\":\"arn:aws:states:::states:startExecution\","
                + "\"Parameters\":" + parametersJson + ",\"End\":true}}}";
    }

    private static final String CHILD_ARN =
            "arn:aws:states:us-east-1:000000000000:stateMachine:child";

    @Test
    void jsonToStringInputBecomesObjectAndNameIsHonored() throws Exception {
        String[] captured = captureChildStart(
                parent("{\"StateMachineArn\":\"" + CHILD_ARN + "\","
                        + "\"Input.$\":\"States.JsonToString($.payload)\",\"Name\":\"child-run-1\"}"),
                "{\"payload\":{\"a\":1}}");
        assertEquals("child-run-1", captured[0], "Name must be honored (was ignored/null before)");
        assertTrue(mapper.readTree(captured[1]).isObject(), "JsonToString Input must reach child as an object");
        assertEquals(1, mapper.readTree(captured[1]).get("a").asInt());
    }

    @Test
    void objectPathInputStaysObject() throws Exception {
        String[] captured = captureChildStart(
                parent("{\"StateMachineArn\":\"" + CHILD_ARN + "\",\"Input.$\":\"$.payload\"}"),
                "{\"payload\":{\"a\":1}}");
        assertNull(captured[0], "no Name -> child gets a generated name (null passed through)");
        assertTrue(mapper.readTree(captured[1]).isObject());
    }

    @Test
    void plainStringInputStaysString() throws Exception {
        String[] captured = captureChildStart(
                parent("{\"StateMachineArn\":\"" + CHILD_ARN + "\",\"Input.$\":\"$.s\"}"),
                "{\"s\":\"hello\"}");
        assertTrue(mapper.readTree(captured[1]).isTextual(), "a plain string Input must not be turned into an object");
        assertEquals("hello", mapper.readTree(captured[1]).asText());
    }

    @Test
    void nameDollarIsHonored() throws Exception {
        String[] captured = captureChildStart(
                parent("{\"StateMachineArn\":\"" + CHILD_ARN + "\",\"Input\":{},\"Name.$\":\"$.nm\"}"),
                "{\"nm\":\"dynamic-name\"}");
        assertEquals("dynamic-name", captured[0]);
    }

    @Test
    void suppliedNonStringNameFailsExecutionAndDoesNotLaunch() {
        // A supplied Name that does not resolve to a non-empty string is a runtime error, not a silent
        // fall-through to a generated name; the child must not be launched.
        Execution exec = runParent(
                parent("{\"StateMachineArn\":\"" + CHILD_ARN + "\",\"Input\":{},\"Name\":{}}"),
                "{}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.Runtime", exec.getError());
        verify(childSfn, never()).startExecution(any(), any(), any(), any());
    }

    // ── Nested StartExecution refusal → typed, catchable error on the optimized states:startExecution
    //    path. AWS reports StepFunctions.<Code>Exception here (the aws-sdk:sfn: path uses Sfn.<Code>),
    //    never the uncatchable States.Runtime it collapsed to before this fix.

    private static final String PARAMS = "{\"StateMachineArn\":\"" + CHILD_ARN + "\"}";

    private static String parentMode(String mode) {
        return "{\"StartAt\":\"Nest\",\"States\":{"
                + "\"Nest\":{\"Type\":\"Task\","
                + "\"Resource\":\"arn:aws:states:::states:startExecution" + mode + "\","
                + "\"Parameters\":" + PARAMS + ",\"End\":true}}}";
    }

    private static String parentCatch(String errorEquals) {
        return "{\"StartAt\":\"Nest\",\"States\":{"
                + "\"Nest\":{\"Type\":\"Task\","
                + "\"Resource\":\"arn:aws:states:::states:startExecution\","
                + "\"Parameters\":" + PARAMS + ","
                + "\"Catch\":[{\"ErrorEquals\":[\"" + errorEquals + "\"],\"Next\":\"Recover\"}],\"End\":true},"
                + "\"Recover\":{\"Type\":\"Pass\",\"End\":true}}}";
    }

    private static String parentRetry(String errorEquals) {
        return "{\"StartAt\":\"Nest\",\"States\":{"
                + "\"Nest\":{\"Type\":\"Task\","
                + "\"Resource\":\"arn:aws:states:::states:startExecution\","
                + "\"Parameters\":" + PARAMS + ","
                + "\"Retry\":[{\"ErrorEquals\":[\"" + errorEquals + "\"],\"MaxAttempts\":1,\"IntervalSeconds\":0}],"
                + "\"End\":true}}}";
    }

    private void childRefuses(String errorCode) {
        doThrow(new AwsException(errorCode, "Execution already exists: " + CHILD_ARN, 400))
                .when(childSfn).startExecution(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".sync", ".sync:2"})
    void childRefusalIsATypedFailure(String mode) {
        childRefuses("ExecutionAlreadyExists");
        Execution exec = runParent(parentMode(mode), "{}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("StepFunctions.ExecutionAlreadyExistsException", exec.getError());
    }

    @Test
    void childRefusalIsCaughtByItsTypedName() {
        childRefuses("ExecutionAlreadyExists");
        Execution exec = runParent(parentCatch("StepFunctions.ExecutionAlreadyExistsException"), "{}");
        assertEquals("SUCCEEDED", exec.getStatus());
    }

    @Test
    void childRefusalIsCaughtByStatesTaskFailed() {
        childRefuses("ExecutionAlreadyExists");
        Execution exec = runParent(parentCatch("States.TaskFailed"), "{}");
        assertEquals("SUCCEEDED", exec.getStatus());
    }

    @Test
    void childRefusalIsRetriedByItsTypedName() {
        childRefuses("ExecutionAlreadyExists");
        Execution exec = runParent(parentRetry("StepFunctions.ExecutionAlreadyExistsException"), "{}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("StepFunctions.ExecutionAlreadyExistsException", exec.getError());
        // MaxAttempts=1: the initial attempt plus one retry both reach the child.
        verify(childSfn, times(2)).startExecution(any(), any(), any(), any());
    }

    @Test
    void sfnPrefixedCatchDoesNotMatchTheOptimizedPath() {
        // The aws-sdk integration's Sfn. prefix must NOT catch the optimized path's StepFunctions. error
        // (catchMatches is exact for non-States names), so the task still fails with the typed name.
        childRefuses("ExecutionAlreadyExists");
        Execution exec = runParent(parentCatch("Sfn.ExecutionAlreadyExistsException"), "{}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("StepFunctions.ExecutionAlreadyExistsException", exec.getError());
    }

    @Test
    void missingChildIsATypedFailure() {
        childRefuses("StateMachineDoesNotExist");
        Execution exec = runParent(parentMode(""), "{}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("StepFunctions.StateMachineDoesNotExistException", exec.getError());
    }

    /**
     * Measured on AWS: .sync returns the child's DescribeExecution response in PascalCase, keys in
     * alphabetical order, dates in epoch milliseconds, Input and Output as JSON strings.
     */
    @Test
    void syncReturnsTheExecutionEnvelopeWithPayloadsAsJsonStrings() {
        childSucceeds();

        Execution exec = runParent(parentMode(".sync"), "{}");

        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals("{\"ExecutionArn\":\"arn:aws:states:us-east-1:000000000000:execution:child:e1\","
                + "\"Input\":\"{\\\"k\\\":1}\",\"InputDetails\":{\"Included\":true},\"Name\":\"e1\","
                + "\"Output\":\"{\\\"ok\\\":true}\",\"OutputDetails\":{\"Included\":true},"
                + "\"RedriveCount\":0,\"RedriveStatus\":\"NOT_REDRIVABLE\","
                + "\"RedriveStatusReason\":\"Execution is SUCCEEDED and cannot be redriven\","
                + "\"StartDate\":1000,\"StateMachineArn\":\"" + CHILD_ARN + "\","
                + "\"Status\":\"SUCCEEDED\",\"StopDate\":2000}", exec.getOutput());
    }

    /** Measured on AWS: .sync:2 returns the same envelope with Input and Output as JSON values. */
    @Test
    void sync2ReturnsTheExecutionEnvelopeWithPayloadsAsJsonValues() {
        childSucceeds();

        Execution exec = runParent(parentMode(".sync:2"), "{}");

        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals("{\"ExecutionArn\":\"arn:aws:states:us-east-1:000000000000:execution:child:e1\","
                + "\"Input\":{\"k\":1},\"InputDetails\":{\"Included\":true},\"Name\":\"e1\","
                + "\"Output\":{\"ok\":true},\"OutputDetails\":{\"Included\":true},"
                + "\"RedriveCount\":0,\"RedriveStatus\":\"NOT_REDRIVABLE\","
                + "\"RedriveStatusReason\":\"Execution is SUCCEEDED and cannot be redriven\","
                + "\"StartDate\":1000,\"StateMachineArn\":\"" + CHILD_ARN + "\","
                + "\"Status\":\"SUCCEEDED\",\"StopDate\":2000}", exec.getOutput());
    }

    /** Measured on AWS: a child started through an alias carries the alias and its version. */
    @Test
    void syncEnvelopeCarriesTheAliasAndVersionTheChildWasStartedThrough() throws Exception {
        Execution done = childSucceeds();
        done.setStateMachineAliasArn(CHILD_ARN + ":live");
        done.setStateMachineVersionArn(CHILD_ARN + ":1");

        Execution exec = runParent(parentMode(".sync:2"), "{}");

        List<String> keys = new ArrayList<>();
        mapper.readTree(exec.getOutput()).fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("ExecutionArn", "Input", "InputDetails", "Name", "Output", "OutputDetails",
                "RedriveCount", "RedriveStatus", "RedriveStatusReason", "StartDate", "StateMachineAliasArn",
                "StateMachineArn", "StateMachineVersionArn", "Status", "StopDate"), keys);
    }

    /**
     * Measured on AWS: request-response returns the StartExecution response in PascalCase with
     * StartDate in epoch milliseconds (and the SDK metadata, which Floci omits).
     */
    @Test
    void requestResponseReturnsTheStartExecutionResponseInPascalCase() {
        Execution exec = runParent(parentMode(""), "{}");

        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals("{\"ExecutionArn\":\"arn:aws:states:us-east-1:000000000000:execution:child:e1\","
                + "\"StartDate\":1000}", exec.getOutput());
    }

    /**
     * AWS records TaskSubmitted once StartExecution has returned and before it waits on the child
     * (measured: resourceType states, resource startExecution.sync:2, the response in PascalCase with
     * StartDate in epoch milliseconds, chained to TaskStarted). It also names the integration as
     * states / startExecution.sync:2 on every event, not startExecution.sync / 2.
     */
    @ParameterizedTest
    @ValueSource(strings = {".sync", ".sync:2"})
    void syncRecordsTaskSubmittedWithTheStartExecutionResponse(String mode) throws Exception {
        childSucceeds();

        Execution exec = runParent(parentMode(mode), "{}");

        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals(List.of("TaskStateEntered", "TaskScheduled", "TaskStarted", "TaskSubmitted",
                "TaskSucceeded", "TaskStateExited", "ExecutionSucceeded"), types());
        HistoryEvent submitted = history.get(3);
        assertEquals(history.get(2).getId(), submitted.getPreviousEventId().longValue());
        assertEquals(submitted.getId(), history.get(4).getPreviousEventId().longValue());
        assertEquals("states", submitted.getDetails().get("resourceType"));
        assertEquals("startExecution" + mode, submitted.getDetails().get("resource"));
        assertEquals("{\"ExecutionArn\":\"arn:aws:states:us-east-1:000000000000:execution:child:e1\","
                + "\"StartDate\":1000}", submitted.getDetails().get("output"));
        assertEquals(Map.of("truncated", false), submitted.getDetails().get("outputDetails"));
        for (int i = 1; i <= 4; i++) {
            assertEquals("states", history.get(i).getDetails().get("resourceType"), history.get(i).getType());
            assertEquals("startExecution" + mode, history.get(i).getDetails().get("resource"), history.get(i).getType());
        }
    }

    /**
     * TaskSubmitted is the first event after the child exists. When it is the one that hits the
     * 25,000-event limit, the wait that would abort the child is never entered, so the child is
     * aborted on the way out instead of running on.
     */
    @Test
    void aChildWhoseTaskSubmittedHitsTheHistoryLimitIsAborted() {
        List<HistoryEvent> recorded = new ArrayList<>();
        for (int i = 0; i < 24_996; i++) {
            recorded.add(new HistoryEvent());
        }

        Execution exec = runParent(parentMode(".sync:2"), "{}", recorded);

        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.Runtime", exec.getError());
        assertEquals("The execution reached the maximum number of history events (25000).", exec.getCause());
        assertEquals("TaskStarted", history.get(24_998).getType());
        assertFalse(types().contains("TaskSubmitted"), "the event that hit the limit is not recorded");
        verify(childSfn).stopExecution(eq("arn:aws:states:us-east-1:000000000000:execution:child:e1"),
                eq("The Task state in AWS Step Functions execution "
                        + "[arn:aws:states:us-east-1:000000000000:execution:parent:pe]"
                        + " which was managing this resource was aborted"), isNull());
    }

    @Test
    void requestResponseRecordsNoTaskSubmitted() {
        Execution exec = runParent(parentMode(""), "{}");

        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertFalse(types().contains("TaskSubmitted"), types().toString());
    }

    private Execution childSucceeds() {
        Execution done = new Execution();
        done.setExecutionArn("arn:aws:states:us-east-1:000000000000:execution:child:e1");
        done.setStateMachineArn(CHILD_ARN);
        done.setName("e1");
        done.setStatus("SUCCEEDED");
        done.setStartDate(1.0);
        done.setStopDate(2.0);
        done.setInput("{\"k\":1}");
        done.setOutput("{\"ok\":true}");
        when(childSfn.describeExecution(any())).thenReturn(done);
        return done;
    }

    private List<String> types() {
        return history.stream().map(HistoryEvent::getType).toList();
    }
}

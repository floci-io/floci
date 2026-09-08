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
import io.github.hectorvent.floci.services.stepfunctions.model.MockedResponseStep;
import io.github.hectorvent.floci.services.stepfunctions.model.MockedTestCase;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The history events of the states inside a Parallel branch or a Map iteration, and the cause a
 * failure inside one reports. Every shape here was read off real Step Functions in us-east-1 for
 * issue #2868: the event families a Parallel and a Map publish around their branches, how
 * {@code previousEventId} chains a branch's events to each other and to the state that started
 * them, which failures leave a {@code *StateFailed} event, and the
 * {@code An error occurred while executing the state ...} prefix a cause carries.
 *
 * <p>Branches run concurrently, so the order in which their events interleave is not asserted.
 * What is asserted is what a reader needs to follow each branch: the chain.
 */
@QuarkusTest
class AslExecutorBranchHistoryEventsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AslExecutor executor;

    @Inject
    Vertx vertx;

    @BeforeEach
    void setUp() {
        Instance<StepFunctionsService> sfnService = mock(Instance.class);
        when(sfnService.get()).thenReturn(mock(StepFunctionsService.class));
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
                sfnService, mock(EmulatorConfig.class), vertx,
                mock(io.github.hectorvent.floci.core.common.CustomResourceLiveness.class));
    }

    @Test
    void parallelBranchesPublishTheirStatesOnChainsOfTheirOwn() {
        var history = run("""
                {"StartAt":"P","States":{
                  "P":{"Type":"Parallel","Branches":[
                    {"StartAt":"A1","States":{"A1":{"Type":"Pass","Next":"A2"},"A2":{"Type":"Pass","End":true}}},
                    {"StartAt":"B1","States":{"B1":{"Type":"Pass","Next":"B2"},"B2":{"Type":"Pass","End":true}}}
                  ],"Next":"After"},
                  "After":{"Type":"Pass","End":true}}}
                """, "{\"x\":\"a\"}");

        assertEquals("SUCCEEDED", status(history), typesOf(history).toString());
        assertEquals(16, history.size(), typesOf(history).toString());
        assertIdsAreConsecutive(history);
        assertEquals(List.of("ExecutionStarted", "ParallelStateEntered", "ParallelStateStarted"),
                typesOf(history.subList(0, 3)));
        assertEquals(List.of("ParallelStateSucceeded", "ParallelStateExited", "PassStateEntered",
                        "PassStateExited", "ExecutionSucceeded"),
                typesOf(history.subList(11, 16)));

        var started = eventOfType(history, "ParallelStateStarted");
        assertEquals(2L, started.getPreviousEventId());
        assertNull(started.getDetails());
        // Each branch's first state starts from ParallelStateStarted, and the branch chains on from
        // there: A1 exited points at A1 entered, A2 entered at A1 exited.
        for (String branch : List.of("A", "B")) {
            var firstEntered = stateEvent(history, "PassStateEntered", branch + "1");
            var firstExited = stateEvent(history, "PassStateExited", branch + "1");
            var secondEntered = stateEvent(history, "PassStateEntered", branch + "2");
            var secondExited = stateEvent(history, "PassStateExited", branch + "2");
            assertEquals(started.getId(), firstEntered.getPreviousEventId());
            assertEquals(firstEntered.getId(), firstExited.getPreviousEventId());
            assertEquals(firstExited.getId(), secondEntered.getPreviousEventId());
            assertEquals(secondEntered.getId(), secondExited.getPreviousEventId());
        }
        // The Parallel continues from the last event a branch published. ParallelStateSucceeded
        // points at it and is passed by: ParallelStateExited points at it too.
        long lastBranchEvent = history.get(10).getId();
        var succeeded = eventOfType(history, "ParallelStateSucceeded");
        var exited = eventOfType(history, "ParallelStateExited");
        assertEquals(lastBranchEvent, succeeded.getPreviousEventId());
        assertNull(succeeded.getDetails());
        assertEquals(lastBranchEvent, exited.getPreviousEventId());
        assertEquals("[{\"x\":\"a\"},{\"x\":\"a\"}]", exited.getDetails().get("output"));
        assertEquals(exited.getId(), stateEvent(history, "PassStateEntered", "After").getPreviousEventId());
    }

    /** The shape reported in issue #2868, with the branch that never fails left out. */
    @Test
    void runtimeErrorInABranchNamesTheBranchStateAndItsEnteredEvent() {
        var history = run("""
                {"StartAt":"P","States":{
                  "P":{"Type":"Parallel","Branches":[
                    {"StartAt":"Fast","States":{"Fast":{"Type":"Pass","Parameters":{"m.$":"$.nope"},"End":true}}}
                  ],"End":true}}}
                """, "{\"x\":\"a\"}");

        assertEquals(List.of("ExecutionStarted", "ParallelStateEntered", "ParallelStateStarted",
                "PassStateEntered", "ExecutionFailed"), typesOf(history));
        assertEquals(List.of(0L, 0L, 2L, 3L, 4L), previousEventIdsOf(history));
        var failed = history.get(4);
        assertEquals("States.Runtime", failed.getDetails().get("error"));
        assertEquals("An error occurred while executing the state 'Fast' (entered at the event id #4). "
                + "The JSONPath '$.nope' specified for the field 'm.$' could not be found in the input '{\"x\":\"a\"}'",
                failed.getDetails().get("cause"));
    }

    @Test
    void failStateInABranchPassesItsCauseThroughAndLeavesAParallelStateFailedEvent() {
        var history = run("""
                {"StartAt":"P","States":{
                  "P":{"Type":"Parallel","Branches":[
                    {"StartAt":"Boom","States":{"Boom":{"Type":"Fail","Error":"MyError","Cause":"my cause"}}}
                  ],"End":true}}}
                """, "{\"x\":\"a\"}");

        assertEquals(List.of("ExecutionStarted", "ParallelStateEntered", "ParallelStateStarted",
                "FailStateEntered", "ParallelStateFailed", "ExecutionFailed"), typesOf(history));
        assertEquals(List.of(0L, 0L, 2L, 3L, 4L, 5L), previousEventIdsOf(history));
        assertNull(history.get(4).getDetails());
        assertEquals(Map.of("error", "MyError", "cause", "my cause"), history.get(5).getDetails());
    }

    @Test
    void caughtBranchFailureExitsTheParallelWithTheErrorOutput() {
        var history = run("""
                {"QueryLanguage":"JSONata","StartAt":"P","States":{
                  "P":{"Type":"Parallel","Branches":[
                    {"StartAt":"Undef","States":{"Undef":{"Type":"Pass","Output":{"v":"{% $states.input.missing %}"},"End":true}}}
                  ],"Catch":[{"ErrorEquals":["States.ALL"],"Next":"Handled"}],"End":true},
                  "Handled":{"Type":"Pass","End":true}}}
                """, "{\"x\":\"a\"}");

        assertEquals(List.of("ExecutionStarted", "ParallelStateEntered", "ParallelStateStarted",
                "PassStateEntered", "EvaluationFailed", "ParallelStateFailed", "ParallelStateExited",
                "PassStateEntered", "PassStateExited", "ExecutionSucceeded"), typesOf(history));
        assertEquals(List.of(0L, 0L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), previousEventIdsOf(history));
        var cause = "An error occurred while executing the state 'Undef' (entered at the event id #4). "
                + "The JSONata expression '$states.input.missing' specified for the field 'Output/v' "
                + "returned nothing (undefined).";
        assertEquals(Map.of("error", "States.QueryEvaluationError", "cause", cause, "location", "Output/v",
                "state", "Undef"), history.get(4).getDetails());
        var errorOutput = "{\"Error\":\"States.QueryEvaluationError\",\"Cause\":\"" + cause.replace("\"", "\\\"") + "\"}";
        assertEquals(errorOutput, history.get(6).getDetails().get("output"));
        assertEquals(errorOutput, history.get(9).getDetails().get("output"));
    }

    @Test
    void retriedParallelStartsAgainAndFailsOnceItsRetryIsUsedUp() {
        var history = run("""
                {"StartAt":"P","States":{
                  "P":{"Type":"Parallel","Branches":[
                    {"StartAt":"Boom","States":{"Boom":{"Type":"Fail","Error":"MyError","Cause":"my cause"}}}
                  ],"Retry":[{"ErrorEquals":["States.ALL"],"MaxAttempts":1,"IntervalSeconds":0}],"End":true}}}
                """, "{\"x\":\"a\"}");

        assertEquals(List.of("ExecutionStarted", "ParallelStateEntered", "ParallelStateStarted",
                "FailStateEntered", "ParallelStateStarted", "FailStateEntered", "ParallelStateFailed",
                "ExecutionFailed"), typesOf(history));
        assertEquals(List.of(0L, 0L, 2L, 3L, 4L, 5L, 6L, 7L), previousEventIdsOf(history));
    }

    @Test
    void parallelOwnFailureIsAttributedToTheParallelAfterItsBranchesSucceeded() {
        var history = run("""
                {"StartAt":"P","States":{
                  "P":{"Type":"Parallel","Branches":[
                    {"StartAt":"Ok","States":{"Ok":{"Type":"Pass","End":true}}}
                  ],"ResultSelector":{"m.$":"$.nope"},"End":true}}}
                """, "{\"x\":\"a\"}");

        assertEquals(List.of("ExecutionStarted", "ParallelStateEntered", "ParallelStateStarted",
                "PassStateEntered", "PassStateExited", "ParallelStateSucceeded", "ExecutionFailed"),
                typesOf(history));
        assertEquals(List.of(0L, 0L, 2L, 3L, 4L, 5L, 6L), previousEventIdsOf(history));
        assertEquals("An error occurred while executing the state 'P' (entered at the event id #2). "
                + "The JSONPath '$.nope' specified for the field 'm.$' could not be found in the input '[{\"x\":\"a\"}]'",
                history.get(6).getDetails().get("cause"));
    }

    @Test
    void taskFailureEndingABranchIsRecordedAsAbortedAndPassesItsCauseThrough() {
        var mocks = new MockedTestCase("branch-history-test", "Case", Map.of("Send", List.of(
                new MockedResponseStep(0, 0, null, "SQS.QueueDoesNotExistException", "The specified queue does not exist."))));
        var history = run("""
                {"StartAt":"P","States":{
                  "P":{"Type":"Parallel","Branches":[
                    {"StartAt":"Send","States":{"Send":{"Type":"Task","Resource":"arn:aws:states:::sqs:sendMessage",
                      "Parameters":{"QueueUrl":"https://sqs.us-east-1.amazonaws.com/000000000000/missing","MessageBody":"hi"},"End":true}}}
                  ],"Catch":[{"ErrorEquals":["States.ALL"],"Next":"Handled"}],"End":true},
                  "Handled":{"Type":"Pass","End":true}}}
                """, "{\"x\":\"a\"}", mocks);

        assertEquals(List.of("ExecutionStarted", "ParallelStateEntered", "ParallelStateStarted",
                "TaskStateEntered", "TaskScheduled", "TaskStarted", "TaskFailed", "TaskStateAborted",
                "ParallelStateFailed", "ParallelStateExited", "PassStateEntered", "PassStateExited",
                "ExecutionSucceeded"), typesOf(history));
        assertEquals(List.of(0L, 0L, 2L, 3L, 4L, 5L, 6L, 7L, 7L, 9L, 10L, 11L, 12L), previousEventIdsOf(history));
        assertNull(history.get(7).getDetails());
        assertEquals("{\"Error\":\"SQS.QueueDoesNotExistException\",\"Cause\":\"The specified queue does not exist.\"}",
                history.get(9).getDetails().get("output"));
    }

    @Test
    void mapIterationsPublishTheirStatesUnderIterationEvents() {
        var history = run("""
                {"StartAt":"M","States":{
                  "M":{"Type":"Map","ItemsPath":"$.items","ItemProcessor":{"ProcessorConfig":{"Mode":"INLINE"},
                    "StartAt":"I1","States":{"I1":{"Type":"Pass","Next":"I2"},"I2":{"Type":"Pass","End":true}}},
                  "Next":"After"},
                  "After":{"Type":"Pass","End":true}}}
                """, "{\"items\":[1,2]}");

        assertEquals("SUCCEEDED", status(history), typesOf(history).toString());
        assertEquals(20, history.size(), typesOf(history).toString());
        assertIdsAreConsecutive(history);
        assertEquals(List.of("ExecutionStarted", "MapStateEntered", "MapStateStarted"), typesOf(history.subList(0, 3)));
        var started = history.get(2);
        assertEquals(2L, started.getPreviousEventId());
        assertEquals(Map.of("length", 2), started.getDetails());

        for (int index = 0; index < 2; index++) {
            var iterationStarted = iterationEvent(history, "MapIterationStarted", index);
            var iterationSucceeded = iterationEvent(history, "MapIterationSucceeded", index);
            var input = String.valueOf(index + 1);
            var firstEntered = stateEventWithInput(history, "PassStateEntered", "I1", input);
            var firstExited = stateEventWithOutput(history, "PassStateExited", "I1", input);
            var secondEntered = stateEventWithInput(history, "PassStateEntered", "I2", input);
            var secondExited = stateEventWithOutput(history, "PassStateExited", "I2", input);
            assertEquals(Map.of("name", "M", "index", index), iterationStarted.getDetails());
            assertEquals(started.getId(), iterationStarted.getPreviousEventId());
            assertEquals(iterationStarted.getId(), firstEntered.getPreviousEventId());
            assertEquals(firstEntered.getId(), firstExited.getPreviousEventId());
            assertEquals(firstExited.getId(), secondEntered.getPreviousEventId());
            assertEquals(secondEntered.getId(), secondExited.getPreviousEventId());
            assertEquals(secondExited.getId(), iterationSucceeded.getPreviousEventId());
            assertEquals(Map.of("name", "M", "index", index), iterationSucceeded.getDetails());
        }
        assertEquals(List.of("MapStateSucceeded", "MapStateExited", "PassStateEntered", "PassStateExited",
                "ExecutionSucceeded"), typesOf(history.subList(15, 20)));
        long lastIterationEvent = history.get(14).getId();
        assertEquals("MapIterationSucceeded", history.get(14).getType());
        assertEquals(lastIterationEvent, history.get(15).getPreviousEventId());
        assertNull(history.get(15).getDetails());
        assertEquals(lastIterationEvent, history.get(16).getPreviousEventId());
        assertEquals("[1,2]", history.get(16).getDetails().get("output"));
    }

    @Test
    void mapOverNoItemsExitsFromItsStartedEvent() {
        var history = run("""
                {"StartAt":"M","States":{
                  "M":{"Type":"Map","ItemsPath":"$.items",
                    "ItemProcessor":{"StartAt":"I","States":{"I":{"Type":"Pass","End":true}}},"End":true}}}
                """, "{\"items\":[]}");

        assertEquals(List.of("ExecutionStarted", "MapStateEntered", "MapStateStarted", "MapStateSucceeded",
                "MapStateExited", "ExecutionSucceeded"), typesOf(history));
        assertEquals(List.of(0L, 0L, 2L, 3L, 3L, 5L), previousEventIdsOf(history));
    }

    @Test
    void runtimeErrorInABranchTaskLeavesNoAbortedEvent() {
        var history = run("""
                {"StartAt":"P","States":{
                  "P":{"Type":"Parallel","Branches":[
                    {"StartAt":"Send","States":{"Send":{"Type":"Task","Resource":"arn:aws:states:::sqs:sendMessage",
                      "Parameters":{"QueueUrl.$":"$.nope","MessageBody":"hi"},"End":true}}}
                  ],"End":true}}}
                """, "{\"x\":\"a\"}");

        assertEquals(List.of("ExecutionStarted", "ParallelStateEntered", "ParallelStateStarted",
                "TaskStateEntered", "ExecutionFailed"), typesOf(history));
        assertEquals(List.of(0L, 0L, 2L, 3L, 4L), previousEventIdsOf(history));
    }

    @Test
    void mapIterationFailureLeavesIterationAndStateFailedEventsBehind() {
        var history = run("""
                {"QueryLanguage":"JSONata","StartAt":"M","States":{
                  "M":{"Type":"Map","Items":"{% $states.input.items %}","MaxConcurrency":1,
                    "ItemProcessor":{"ProcessorConfig":{"Mode":"INLINE"},"StartAt":"Undef",
                      "States":{"Undef":{"Type":"Pass","Output":{"v":"{% $states.input.missing %}"},"End":true}}},
                  "End":true}}}
                """, "{\"items\":[1,2]}");

        assertEquals(List.of("ExecutionStarted", "MapStateEntered", "MapStateStarted", "MapIterationStarted",
                "PassStateEntered", "EvaluationFailed", "MapIterationFailed", "MapStateFailed", "ExecutionFailed"),
                typesOf(history));
        // MapIterationFailed points at the iteration's last event and is passed by: MapStateFailed
        // points at that same event.
        assertEquals(List.of(0L, 0L, 2L, 3L, 4L, 5L, 6L, 6L, 8L), previousEventIdsOf(history));
        var cause = "An error occurred while executing the state 'Undef' (entered at the event id #5). "
                + "The JSONata expression '$states.input.missing' specified for the field 'Output/v' "
                + "returned nothing (undefined).";
        assertEquals("Undef", history.get(5).getDetails().get("state"));
        assertEquals(Map.of("name", "M", "index", 0), history.get(6).getDetails());
        assertNull(history.get(7).getDetails());
        assertEquals(cause, history.get(8).getDetails().get("cause"));
    }

    @Test
    void runtimeErrorInAMapIterationLeavesNoFailedEvents() {
        var history = run("""
                {"StartAt":"M","States":{
                  "M":{"Type":"Map","ItemsPath":"$.items","MaxConcurrency":1,
                    "ItemProcessor":{"ProcessorConfig":{"Mode":"INLINE"},"StartAt":"Fast",
                      "States":{"Fast":{"Type":"Pass","Parameters":{"m.$":"$.nope"},"End":true}}},
                  "End":true}}}
                """, "{\"items\":[1,2]}");

        assertEquals(List.of("ExecutionStarted", "MapStateEntered", "MapStateStarted", "MapIterationStarted",
                "PassStateEntered", "ExecutionFailed"), typesOf(history));
        assertEquals(List.of(0L, 0L, 2L, 3L, 4L, 5L), previousEventIdsOf(history));
        assertEquals("An error occurred while executing the state 'Fast' (entered at the event id #5). "
                + "The JSONPath '$.nope' specified for the field 'm.$' could not be found in the input '1'",
                history.get(5).getDetails().get("cause"));
    }

    @Test
    void distributedMapNamesItsRunAndPublishesNoItemEvents() {
        var history = run("""
                {"StartAt":"D","States":{
                  "D":{"Type":"Map","ItemsPath":"$.items","ItemProcessor":{
                    "ProcessorConfig":{"Mode":"DISTRIBUTED","ExecutionType":"STANDARD"},
                    "StartAt":"I1","States":{"I1":{"Type":"Pass","End":true}}},
                  "End":true}}}
                """, "{\"items\":[1,2]}");

        assertEquals(List.of("ExecutionStarted", "MapStateEntered", "MapStateStarted", "MapRunStarted",
                "MapRunSucceeded", "MapStateSucceeded", "MapStateExited", "ExecutionSucceeded"), typesOf(history));
        // The parent chain stays at MapRunStarted: MapStateExited points at it, past both Succeeded events.
        assertEquals(List.of(0L, 0L, 2L, 3L, 4L, 5L, 4L, 7L), previousEventIdsOf(history));
        var mapRunArn = (String) history.get(3).getDetails().get("mapRunArn");
        assertTrue(mapRunArn.startsWith("arn:aws:states:us-east-1:000000000000:mapRun:branch-history-test/"), mapRunArn);
        assertEquals("[1,2]", history.get(6).getDetails().get("output"));
    }

    @Test
    void nestedMapInsideAParallelBranchChainsToTheBranch() {
        var history = run("""
                {"StartAt":"P","States":{
                  "P":{"Type":"Parallel","Branches":[
                    {"StartAt":"M","States":{"M":{"Type":"Map","ItemsPath":"$.items","ItemProcessor":{
                      "ProcessorConfig":{"Mode":"INLINE"},"StartAt":"I1","States":{"I1":{"Type":"Pass","End":true}}},
                      "End":true}}},
                    {"StartAt":"B1","States":{"B1":{"Type":"Pass","End":true}}}
                  ],"End":true}}}
                """, "{\"items\":[1,2]}");

        assertEquals("SUCCEEDED", status(history), typesOf(history).toString());
        assertEquals(20, history.size(), typesOf(history).toString());
        assertIdsAreConsecutive(history);
        var parallelStarted = eventOfType(history, "ParallelStateStarted");
        var mapEntered = eventOfType(history, "MapStateEntered");
        var mapStarted = eventOfType(history, "MapStateStarted");
        var mapSucceeded = eventOfType(history, "MapStateSucceeded");
        var mapExited = eventOfType(history, "MapStateExited");
        assertEquals(parallelStarted.getId(), mapEntered.getPreviousEventId());
        assertEquals(parallelStarted.getId(), stateEvent(history, "PassStateEntered", "B1").getPreviousEventId());
        assertEquals(mapEntered.getId(), mapStarted.getPreviousEventId());
        for (int index = 0; index < 2; index++) {
            assertEquals(mapStarted.getId(), iterationEvent(history, "MapIterationStarted", index).getPreviousEventId());
        }
        long lastIterationEvent = Math.max(iterationEvent(history, "MapIterationSucceeded", 0).getId(),
                iterationEvent(history, "MapIterationSucceeded", 1).getId());
        assertEquals(lastIterationEvent, mapSucceeded.getPreviousEventId());
        assertEquals(lastIterationEvent, mapExited.getPreviousEventId());
        var parallelExited = eventOfType(history, "ParallelStateExited");
        long lastBranchEvent = Math.max(mapExited.getId(), stateEvent(history, "PassStateExited", "B1").getId());
        assertEquals(lastBranchEvent, eventOfType(history, "ParallelStateSucceeded").getPreviousEventId());
        assertEquals(lastBranchEvent, parallelExited.getPreviousEventId());
        assertEquals("ExecutionSucceeded", history.get(19).getType());
    }

    @Test
    void jsonataFailureRecordsAnEvaluationFailedEventPerAttempt() {
        var history = run("""
                {"QueryLanguage":"JSONata","StartAt":"Send","States":{
                  "Send":{"Type":"Task","Resource":"arn:aws:states:::sqs:sendMessage",
                    "Arguments":{"QueueUrl":"https://sqs.us-east-1.amazonaws.com/000000000000/q","MessageBody":"{% $states.input.missing %}"},
                    "Retry":[{"ErrorEquals":["States.ALL"],"MaxAttempts":1,"IntervalSeconds":0}],"End":true}}}
                """, "{\"x\":\"a\"}");

        assertEquals(List.of("ExecutionStarted", "TaskStateEntered", "EvaluationFailed", "EvaluationFailed",
                "ExecutionFailed"), typesOf(history));
        assertEquals(List.of(0L, 0L, 2L, 3L, 4L), previousEventIdsOf(history));
        var cause = "An error occurred while executing the state 'Send' (entered at the event id #2). "
                + "The JSONata expression '$states.input.missing' specified for the field 'Arguments/MessageBody' "
                + "returned nothing (undefined).";
        var expected = Map.of("error", "States.QueryEvaluationError", "cause", cause,
                "location", "Arguments/MessageBody", "state", "Send");
        assertEquals(expected, history.get(2).getDetails());
        assertEquals(expected, history.get(3).getDetails());
        assertEquals(Map.of("error", "States.QueryEvaluationError", "cause", cause), history.get(4).getDetails());
    }

    @Test
    void mapItemsExpressionFailureIsRecordedBeforeTheMapStarts() {
        var history = run("""
                {"QueryLanguage":"JSONata","StartAt":"Fan","States":{
                  "Fan":{"Type":"Map","Items":"{% $states.input.missing %}",
                    "ItemProcessor":{"ProcessorConfig":{"Mode":"INLINE"},"StartAt":"I","States":{"I":{"Type":"Pass","End":true}}},
                    "End":true}}}
                """, "{\"x\":\"a\"}");

        assertEquals(List.of("ExecutionStarted", "MapStateEntered", "EvaluationFailed", "MapStateFailed",
                "ExecutionFailed"), typesOf(history));
        assertEquals(List.of(0L, 0L, 2L, 3L, 4L), previousEventIdsOf(history));
        assertEquals("Items", history.get(2).getDetails().get("location"));
    }

    /**
     * A Catch clause whose own Output fails. AWS records the clause's EvaluationFailed from where the
     * state stood before its first Failed event, then fails the state a second time.
     */
    @Test
    void catchClauseOutputFailureIsRecordedFromBeforeTheStateFailedEvent() {
        var history = run("""
                {"QueryLanguage":"JSONata","StartAt":"P","States":{
                  "P":{"Type":"Parallel","Branches":[
                    {"StartAt":"Boom","States":{"Boom":{"Type":"Fail","Error":"MyError","Cause":"my cause"}}}
                  ],"Catch":[{"ErrorEquals":["Other"],"Next":"Done"},
                             {"ErrorEquals":["States.ALL"],"Output":{"v":"{% $states.input.missing %}"},"Next":"Done"}],
                  "End":true},
                  "Done":{"Type":"Pass","End":true}}}
                """, "{\"x\":\"a\"}");

        assertEquals(List.of("ExecutionStarted", "ParallelStateEntered", "ParallelStateStarted", "FailStateEntered",
                "ParallelStateFailed", "EvaluationFailed", "ParallelStateFailed", "ExecutionFailed"), typesOf(history));
        assertEquals(List.of(0L, 0L, 2L, 3L, 4L, 4L, 6L, 7L), previousEventIdsOf(history));
        var cause = "An error occurred while executing the state 'P' (entered at the event id #2). "
                + "The JSONata expression '$states.input.missing' specified for the field 'Catch[1]/Output/v' "
                + "returned nothing (undefined).";
        assertEquals(Map.of("error", "States.QueryEvaluationError", "cause", cause,
                "location", "Catch[1]/Output/v", "state", "P"), history.get(5).getDetails());
        assertEquals(Map.of("error", "States.QueryEvaluationError", "cause", cause), history.get(7).getDetails());
    }

    @Test
    void choiceWithNoMatchingRuleAndNoDefaultFailsAsAwsDoes() {
        var history = run("""
                {"StartAt":"C","States":{
                  "C":{"Type":"Choice","Choices":[{"Variable":"$.x","StringEquals":"never","Next":"Done"}]},
                  "Done":{"Type":"Pass","End":true}}}
                """, "{\"x\":\"a\"}");

        assertEquals(List.of("ExecutionStarted", "ChoiceStateEntered", "ExecutionFailed"), typesOf(history));
        assertEquals(Map.of("error", "States.Runtime",
                "cause", "An error occurred while executing the state 'C' (entered at the event id #2). "
                        + "Failed to transition out of the state. The state does not point to a next state."),
                history.get(2).getDetails());
    }

    @Test
    void catchInsideABranchTakesItsNextEvenOnAnEndState() {
        var mocks = new MockedTestCase("branch-history-test", "Case", Map.of("Send", List.of(
                new MockedResponseStep(0, 0, null, "MyError", "boom"))));
        var history = run("""
                {"StartAt":"P","States":{
                  "P":{"Type":"Parallel","Branches":[
                    {"StartAt":"Send","States":{
                      "Send":{"Type":"Task","Resource":"arn:aws:states:::sqs:sendMessage",
                        "Parameters":{"QueueUrl":"https://sqs.us-east-1.amazonaws.com/000000000000/q","MessageBody":"hi"},
                        "Catch":[{"ErrorEquals":["States.ALL"],"Next":"Recover"}],"End":true},
                      "Recover":{"Type":"Pass","Result":{"recovered":true},"End":true}}}
                  ],"End":true}}}
                """, "{}", mocks);

        assertEquals("SUCCEEDED", status(history), typesOf(history).toString());
        assertEquals("[{\"recovered\":true}]", eventOfType(history, "ExecutionSucceeded").getDetails().get("output"));
        assertFalse(typesOf(history).contains("TaskStateAborted"));
    }

    private static String status(List<HistoryEvent> history) {
        var last = history.get(history.size() - 1).getType();
        return switch (last) {
            case "ExecutionSucceeded" -> "SUCCEEDED";
            case "ExecutionFailed" -> "FAILED";
            default -> last;
        };
    }

    private static List<String> typesOf(List<HistoryEvent> history) {
        return history.stream().map(HistoryEvent::getType).toList();
    }

    private static List<Long> previousEventIdsOf(List<HistoryEvent> history) {
        return history.stream().map(HistoryEvent::getPreviousEventId).toList();
    }

    private static void assertIdsAreConsecutive(List<HistoryEvent> history) {
        for (var i = 0; i < history.size(); i++) {
            assertEquals(i + 1L, history.get(i).getId(), "unexpected id at index " + i);
        }
        var referenced = history.stream().map(HistoryEvent::getPreviousEventId).collect(Collectors.toSet());
        referenced.remove(0L);
        assertTrue(referenced.stream().allMatch(id -> id < history.size()),
                "previousEventId points past the history: " + Set.copyOf(referenced));
    }

    private static HistoryEvent eventOfType(List<HistoryEvent> history, String type) {
        return history.stream()
                .filter(event -> type.equals(event.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("history has no " + type + " event: " + typesOf(history)));
    }

    private static HistoryEvent stateEvent(List<HistoryEvent> history, String type, String name) {
        return history.stream()
                .filter(event -> type.equals(event.getType()) && name.equals(event.getDetails().get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("history has no " + type + " for " + name + ": " + typesOf(history)));
    }

    private static HistoryEvent stateEventWithInput(List<HistoryEvent> history, String type, String name, String input) {
        return history.stream()
                .filter(event -> type.equals(event.getType()) && name.equals(event.getDetails().get("name"))
                        && input.equals(event.getDetails().get("input")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("history has no " + type + " for " + name + " with input "
                        + input + ": " + typesOf(history)));
    }

    private static HistoryEvent stateEventWithOutput(List<HistoryEvent> history, String type, String name, String output) {
        return history.stream()
                .filter(event -> type.equals(event.getType()) && name.equals(event.getDetails().get("name"))
                        && output.equals(event.getDetails().get("output")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("history has no " + type + " for " + name + " with output "
                        + output + ": " + typesOf(history)));
    }

    private static HistoryEvent iterationEvent(List<HistoryEvent> history, String type, int index) {
        return history.stream()
                .filter(event -> type.equals(event.getType()) && Integer.valueOf(index).equals(event.getDetails().get("index")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("history has no " + type + " for index " + index + ": " + typesOf(history)));
    }

    private List<HistoryEvent> run(String definition, String input) {
        return run(definition, input, null);
    }

    /** Runs the definition the way StartExecution does: the history already holds ExecutionStarted as event 1. */
    private List<HistoryEvent> run(String definition, String input, MockedTestCase mocks) {
        var stateMachine = new StateMachine();
        stateMachine.setName("branch-history-test");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:branch-history-test".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        var execution = new Execution();
        execution.setName("branch-history-execution");
        execution.setExecutionArn(
                "arn:aws:states:%s:%s:execution:branch-history-test:branch-history-execution".formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

        var history = new ArrayList<HistoryEvent>();
        var started = new HistoryEvent();
        started.setId(1L);
        started.setPreviousEventId(0L);
        started.setType("ExecutionStarted");
        started.setDetails(Map.of("input", input, "inputDetails", Map.of("truncated", false)));
        history.add(started);

        executor.executeSync(stateMachine, execution, history, mocks, (updated, events) -> {
        });
        return history;
    }
}

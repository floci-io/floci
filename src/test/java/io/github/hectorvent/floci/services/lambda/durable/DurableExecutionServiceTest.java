package io.github.hectorvent.floci.services.lambda.durable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.lambda.durable.DurableExecutionService.CheckpointResult;
import io.github.hectorvent.floci.services.lambda.durable.DurableExecutionService.ListRequest;
import io.github.hectorvent.floci.services.lambda.durable.DurableExecutionService.StartRequest;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableErrorObject;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecution;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecutionStatus;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableHistoryEvent;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperation;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationAction;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationStatus;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationType;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationUpdate;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the engine with a scripted stand-in for the function. The script receives the invocation
 * event, checkpoints through the service like the SDK would, and returns the handler response.
 * An inline executor and a zero retry delay make every invocation chain run on the test thread.
 */
class DurableExecutionServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String FUNCTION = "durable-fn";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:durable-fn:1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MutableClock clock = new MutableClock();
    private InMemoryStorageFactory storage;
    private ScriptedInvoker invoker;
    private DurableExecutionService service;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorageFactory(ACCOUNT);
        invoker = new ScriptedInvoker();
        service = newService(storage, invoker, clock);
    }

    @Test
    void startInvokesTheFunctionWithTheExecutionOperationAndAToken() {
        invoker.script(event -> succeeded("\"done\""));

        DurableExecution execution = start("exec-1", "{\"a\":1}", true);

        JsonNode event = invoker.events.get(0);
        assertEquals(execution.getExecutionArn(), event.get("DurableExecutionArn").asText());
        assertTrue(execution.getExecutionArn().startsWith(FUNCTION_ARN + "/durable-execution/exec-1/"));
        assertFalse(event.get("CheckpointToken").asText().isEmpty());
        assertEquals(execution.getExecutionId(), event.get("UpdatedOperationIds").get(0).asText());
        JsonNode root = event.get("InitialExecutionState").get("Operations").get(0);
        assertEquals(execution.getExecutionId(), root.get("Id").asText());
        assertEquals("EXECUTION", root.get("Type").asText());
        assertEquals("STARTED", root.get("Status").asText());
        assertEquals("{\"a\":1}", root.get("ExecutionDetails").get("InputPayload").asText());
        assertEquals("", event.get("InitialExecutionState").get("NextMarker").asText());
        assertEquals(DurableExecutionStatus.SUCCEEDED, service.get(execution.getExecutionArn()).getStatus());
        assertEquals("\"done\"", service.get(execution.getExecutionArn()).getResult());
    }

    @Test
    void stepCheckpointIsReturnedAsNewStateAndTheResultClosesTheExecution() {
        invoker.script(event -> {
            CheckpointResult first = checkpoint(event, token(event),
                    List.of(step("s1", DurableOperationAction.START, null, null), step("s1",
                            DurableOperationAction.SUCCEED, "42", null)));
            assertEquals(1, first.newExecutionState().size());
            assertEquals(DurableOperationStatus.SUCCEEDED, first.newExecutionState().get(0).getStatus());
            assertEquals(1, first.newExecutionState().get(0).getAttempt());
            assertNotNull(first.checkpointToken());
            CheckpointResult poll = checkpoint(event, first.checkpointToken(), List.of());
            assertTrue(poll.newExecutionState().isEmpty(), "nothing changed since the last response");
            return succeeded("\"ok\"");
        });

        DurableExecution execution = service.get(start("exec-1", "{}", true).getExecutionArn());

        assertEquals(DurableExecutionStatus.SUCCEEDED, execution.getStatus());
        assertEquals("\"ok\"", execution.getResult());
        assertEquals(List.of("ExecutionStarted", "StepStarted", "StepSucceeded", "InvocationCompleted",
                "ExecutionSucceeded"), eventTypes(execution));
        assertNull(execution.getCurrentInvocationId());
    }

    @Test
    void waitCompletesWhenTheSweepReachesItsDeadlineAndTheFunctionIsReinvoked() {
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(waitStart("w1", 5)));
            return pending();
        });
        invoker.script(event -> {
            JsonNode wait = operation(event, "w1");
            assertEquals("SUCCEEDED", wait.get("Status").asText());
            assertEquals("w1", event.get("UpdatedOperationIds").get(0).asText());
            assertEquals(1, event.get("UpdatedOperationIds").size());
            return succeeded("\"after wait\"");
        });

        String arn = start("exec-1", "{}", false).getExecutionArn();
        assertEquals(DurableExecutionStatus.RUNNING, service.get(arn).getStatus());
        service.sweep();
        assertEquals(1, invoker.events.size(), "the wait is not due yet");

        clock.advance(Duration.ofSeconds(5));
        service.sweep();

        assertEquals(2, invoker.events.size());
        assertEquals(DurableExecutionStatus.SUCCEEDED, service.get(arn).getStatus());
        assertTrue(eventTypes(service.get(arn)).containsAll(List.of("WaitStarted", "WaitSucceeded")));
    }

    @Test
    void stepRetryBecomesReadyAfterItsDelay() {
        invoker.script(event -> {
            CheckpointResult result = checkpoint(event, token(event), List.of(
                    step("s1", DurableOperationAction.START, null, null),
                    stepRetry("s1", 3, DurableErrorObject.of("boom", "Error"))));
            assertEquals(DurableOperationStatus.PENDING, result.newExecutionState().get(0).getStatus());
            assertNotNull(result.newExecutionState().get(0).getNextAttemptTimestamp());
            return pending();
        });
        invoker.script(event -> {
            assertEquals("READY", operation(event, "s1").get("Status").asText());
            assertFalse(operation(event, "s1").get("StepDetails").has("NextAttemptTimestamp"));
            checkpoint(event, token(event), List.of(step("s1", DurableOperationAction.START, null, null),
                    step("s1", DurableOperationAction.SUCCEED, "ok", null)));
            return succeeded("\"done\"");
        });

        String arn = start("exec-1", "{}", false).getExecutionArn();
        clock.advance(Duration.ofSeconds(3));
        service.sweep();

        DurableExecution execution = service.get(arn);
        assertEquals(DurableExecutionStatus.SUCCEEDED, execution.getStatus());
        assertEquals(2, execution.getOperations().get("s1").getAttempt());
        assertTrue(eventTypes(execution).contains("StepFailed"), "a RETRY with an error records StepFailed");
    }

    @Test
    void emptyCheckpointCompletesADueWaitInsideTheInvocation() {
        invoker.script(event -> {
            String token = checkpoint(event, token(event), List.of(waitStart("w1", 1))).checkpointToken();
            clock.advance(Duration.ofSeconds(1));
            CheckpointResult poll = checkpoint(event, token, List.of());
            assertEquals(1, poll.newExecutionState().size());
            assertEquals(DurableOperationStatus.SUCCEEDED, poll.newExecutionState().get(0).getStatus());
            return succeeded("\"x\"");
        });

        assertEquals(DurableExecutionStatus.SUCCEEDED, service.get(start("exec-1", "{}", true).getExecutionArn())
                .getStatus());
    }

    @Test
    void staleTokensAndTokensOfClosedExecutionsAreRejected() {
        invoker.script(event -> {
            String first = token(event);
            String second = checkpoint(event, first, List.of()).checkpointToken();
            AwsException stale = assertThrows(AwsException.class, () -> checkpoint(event, first, List.of()));
            assertEquals("Invalid checkpoint token", stale.getMessage());
            assertEquals(400, stale.getHttpStatus());
            assertThrows(AwsException.class, () -> checkpoint(event, "QUJDRA==", List.of()));
            String arn = event.get("DurableExecutionArn").asText();
            String otherArn = arn.substring(0, arn.lastIndexOf('/') + 1) + "other-id";
            AwsException other = assertThrows(AwsException.class,
                    () -> service.checkpoint(otherArn, second, null, List.of()));
            assertEquals("Checkpoint token is not valid for the durable execution ARN", other.getMessage());
            CheckpointResult closing = checkpoint(event, second, List.of(executionSucceed("\"r\"")));
            assertNull(closing.checkpointToken(), "the closing checkpoint carries no token");
            assertThrows(AwsException.class, () -> checkpoint(event, second, List.of()));
            return succeeded("");
        });

        DurableExecution execution = service.get(start("exec-1", "{}", true).getExecutionArn());
        assertEquals(DurableExecutionStatus.SUCCEEDED, execution.getStatus());
        assertEquals("\"r\"", execution.getResult(), "the checkpointed result wins over the handler return");
    }

    @Test
    void aClosingCheckpointEndsTheInvocationAndTheHandlerReturnIsIgnored() {
        List<CheckpointResult> closing = new ArrayList<>();
        invoker.script(event -> {
            closing.add(checkpoint(event, token(event), List.of(step("s1", DurableOperationAction.START, null, null),
                    executionSucceed("\"closed\""))));
            return functionError("{\"errorMessage\":\"after close\"}");
        });

        DurableExecution execution = service.get(start("exec-1", "{}", false).getExecutionArn());

        assertEquals(DurableExecutionStatus.SUCCEEDED, execution.getStatus());
        assertEquals("\"closed\"", execution.getResult());
        assertEquals(List.of("ExecutionStarted", "StepStarted", "InvocationCompleted", "ExecutionSucceeded"),
                eventTypes(execution));
        Map<?, ?> completed = (Map<?, ?>) execution.getHistory().get(2).getDetails().get("InvocationCompletedDetails");
        assertEquals(invoker.requestIds.get(0), completed.get("RequestId"));
        assertTrue(closing.get(0).newExecutionState().isEmpty());
    }

    @Test
    void aRetriedCheckpointWithTheSameClientTokenGetsTheSameAnswer() {
        invoker.script(event -> {
            String arn = event.get("DurableExecutionArn").asText();
            String first = token(event);
            List<DurableOperationUpdate> updates = List.of(waitStart("w1", 1));
            CheckpointResult accepted = service.checkpoint(arn, first, "ct-1", updates);
            clock.advance(Duration.ofSeconds(2));
            service.sweep();
            CheckpointResult retried = service.checkpoint(arn, first, "ct-1", List.of());
            assertEquals(accepted.checkpointToken(), retried.checkpointToken());
            assertEquals(DurableOperationStatus.STARTED, retried.newExecutionState().get(0).getStatus(),
                    "the retry gets the answer as it was sent, not the fired wait");
            assertThrows(AwsException.class, () -> service.checkpoint(arn, first, "ct-2", updates));
            CheckpointResult next = service.checkpoint(arn, accepted.checkpointToken(), "ct-3", List.of());
            assertEquals(DurableOperationStatus.SUCCEEDED, next.newExecutionState().get(0).getStatus());
            assertThrows(AwsException.class, () -> service.checkpoint(arn, first, "ct-1", updates));
            return succeeded("");
        });

        DurableExecution execution = service.get(start("exec-1", "{}", true).getExecutionArn());
        assertEquals(List.of("ExecutionStarted", "WaitStarted", "WaitSucceeded", "InvocationCompleted",
                "ExecutionSucceeded"), eventTypes(execution));
    }

    @Test
    void sameNameIsIdempotentForTheSamePayloadAndRejectedForAnotherPayload() {
        invoker.script(event -> succeeded("\"one\""));

        DurableExecution first = start("same", "{\"a\":1}", true);
        DurableExecution again = start("same", "{\"a\":1}", true);
        assertEquals(first.getExecutionArn(), again.getExecutionArn());
        assertEquals(1, invoker.events.size());

        AwsException conflict = assertThrows(AwsException.class, () -> start("same", "{\"a\":2}", true));
        assertEquals("DurableExecutionAlreadyStartedException", conflict.getErrorCode());
        assertEquals("Execution already started: " + first.getExecutionArn(), conflict.getMessage());
        assertEquals(409, conflict.getHttpStatus());

        AwsException otherVersion = assertThrows(AwsException.class, () -> service.start(
                new StartRequest(ACCOUNT, REGION, FUNCTION, "2", "same", "{\"a\":1}", true)));
        assertEquals("Execution already started: " + first.getExecutionArn(), otherVersion.getMessage());
    }

    @Test
    void handlerResponsesThatBreakTheProtocolFailTheExecution() {
        invoker.script(event -> handlerResponse("{\"foo\":1}"));
        DurableExecution invalid = service.get(start("invalid", "{}", true).getExecutionArn());
        assertEquals(DurableExecutionStatus.FAILED, invalid.getStatus());
        assertEquals("Invalid Status in invocation output.", invalid.getError().getErrorMessage());
        assertEquals("InvalidParameterValueException", invalid.getError().getErrorType());
        assertEquals(List.of("ExecutionStarted", "InvocationCompleted", "ExecutionFailed"), eventTypes(invalid));

        invoker.script(event -> pending());
        DurableExecution idle = service.get(start("idle", "{}", true).getExecutionArn());
        assertEquals(DurableExecutionStatus.FAILED, idle.getStatus());
        assertEquals("Cannot return PENDING status with no pending operations.", idle.getError().getErrorMessage());

        invoker.script(event -> handlerResponse("{\"Status\":\"FAILED\",\"Error\":{\"ErrorMessage\":\"bad\","
                + "\"ErrorType\":\"MyError\"}}"));
        DurableExecution failed = service.get(start("failed", "{}", true).getExecutionArn());
        assertEquals(DurableExecutionStatus.FAILED, failed.getStatus());
        assertEquals("MyError", failed.getError().getErrorType());
    }

    @Test
    void aFunctionErrorIsRetriedAndFailsTheExecutionAfterTheLastAttempt() {
        for (int i = 0; i < DurableExecutionService.INVOCATION_RETRY_MAX_ATTEMPTS; i++) {
            invoker.script(event -> functionError("{\"errorMessage\":\"crash\",\"errorType\":\"RuntimeError\"}"));
        }

        String arn = start("exec-1", "{}", false).getExecutionArn();
        for (int i = 1; i < DurableExecutionService.INVOCATION_RETRY_MAX_ATTEMPTS; i++) {
            assertEquals(DurableExecutionStatus.RUNNING, service.get(arn).getStatus());
            service.sweep();
        }

        DurableExecution execution = service.get(arn);
        assertEquals(DurableExecutionStatus.FAILED, execution.getStatus());
        assertEquals("crash", execution.getError().getErrorMessage());
        assertEquals("RuntimeError", execution.getError().getErrorType());
        assertEquals(DurableExecutionService.INVOCATION_RETRY_MAX_ATTEMPTS,
                eventTypes(execution).stream().filter("InvocationCompleted"::equals).count());
    }

    @Test
    void stopClosesTheExecutionAndIsIdempotent() {
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(waitStart("w1", 60)));
            return pending();
        });
        String arn = start("exec-1", "{}", false).getExecutionArn();
        String token = token(invoker.events.get(0));

        DurableExecution stopped = service.stop(arn, null);
        assertEquals(DurableExecutionStatus.STOPPED, stopped.getStatus());
        assertNotNull(stopped.getError(), "a stop without a body records an empty error object");
        assertNull(stopped.getError().getErrorMessage());
        assertEquals(stopped.getEndTimestamp(), service.stop(arn, DurableErrorObject.of("x", "y")).getEndTimestamp());
        assertNull(service.get(arn).getError().getErrorType(), "a second stop does not overwrite the first");
        assertThrows(AwsException.class, () -> service.checkpoint(arn, token, null, List.of()));
        assertEquals("ExecutionStopped", eventTypes(service.get(arn)).getLast());

        clock.advance(Duration.ofSeconds(60));
        service.sweep();
        assertEquals(1, invoker.events.size(), "a stopped execution is never re-invoked");
    }

    @Test
    void executionTimeoutMarksTheExecutionTimedOut() {
        invoker.executionTimeoutSeconds = 10;
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(waitStart("w1", 60)));
            return pending();
        });
        String arn = start("exec-1", "{}", false).getExecutionArn();

        clock.advance(Duration.ofSeconds(10));
        service.sweep();

        DurableExecution execution = service.get(arn);
        assertEquals(DurableExecutionStatus.TIMED_OUT, execution.getStatus());
        assertEquals("Execution timed out after 10 seconds.", execution.getError().getErrorMessage());
        assertEquals("ExecutionTimedOut", eventTypes(execution).getLast());
    }

    @Test
    void listFiltersAndPagesNewestFirst() {
        invoker.script(event -> succeeded("1"));
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(waitStart("w1", 60)));
            return pending();
        });
        invoker.script(event -> succeeded("3"));
        String first = start("a", "{}", true).getExecutionArn();
        String second = start("b", "{}", false).getExecutionArn();
        String third = start("c", "{}", true).getExecutionArn();

        PaginatedResult<DurableExecution> all = service.list(listRequest(null, null, null, null));
        assertEquals(List.of(third, second, first), all.items().stream().map(DurableExecution::getExecutionArn).toList());
        assertNull(all.nextToken());

        PaginatedResult<DurableExecution> running = service.list(listRequest(Set.of(DurableExecutionStatus.RUNNING),
                null, null, null));
        assertEquals(List.of(second), running.items().stream().map(DurableExecution::getExecutionArn).toList());

        PaginatedResult<DurableExecution> byName = service.list(listRequest(null, "c", null, null));
        assertEquals(List.of(third), byName.items().stream().map(DurableExecution::getExecutionArn).toList());

        PaginatedResult<DurableExecution> page = service.list(listRequest(null, null, 2, null));
        assertEquals(2, page.items().size());
        assertNotNull(page.nextToken());
        PaginatedResult<DurableExecution> rest = service.list(listRequest(null, null, 2, page.nextToken()));
        assertEquals(List.of(first), rest.items().stream().map(DurableExecution::getExecutionArn).toList());
        assertNull(rest.nextToken());
    }

    @Test
    void anEmptyStatusFilterMatchesNothingButStillChecksTheMarker() {
        invoker.script(event -> succeeded("1"));
        start("a", "{}", true);

        assertTrue(service.list(listRequest(Set.of(), null, null, null)).items().isEmpty());
        assertThrows(AwsException.class, () -> service.list(listRequest(Set.of(), null, null, "bogus")));
    }

    @Test
    void historyPagesInBothDirections() {
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(step("s1", DurableOperationAction.START, null, null),
                    step("s1", DurableOperationAction.SUCCEED, "1", null)));
            return succeeded("\"x\"");
        });
        String arn = start("exec-1", "{}", true).getExecutionArn();

        PaginatedResult<DurableHistoryEvent> firstPage = service.history(arn, 2, null, false);
        assertEquals(List.of(1L, 2L), firstPage.items().stream().map(DurableHistoryEvent::getEventId).toList());
        PaginatedResult<DurableHistoryEvent> secondPage = service.history(arn, 2, firstPage.nextToken(), false);
        assertEquals(List.of(3L, 4L), secondPage.items().stream().map(DurableHistoryEvent::getEventId).toList());
        PaginatedResult<DurableHistoryEvent> newestFirst = service.history(arn, 1, null, true);
        assertEquals("ExecutionSucceeded", newestFirst.items().get(0).getEventType());
        assertThrows(AwsException.class, () -> service.history(arn, 2, "bogus", false));
    }

    @Test
    void aClosedExecutionIsDeletedWhenItsRetentionExpires() {
        invoker.retentionPeriodInDays = 1;
        invoker.script(event -> succeeded("1"));
        String arn = start("exec-1", "{}", true).getExecutionArn();

        clock.advance(Duration.ofHours(23));
        service.sweep();
        assertEquals(DurableExecutionStatus.SUCCEEDED, service.get(arn).getStatus());

        clock.advance(Duration.ofHours(2));
        service.sweep();
        AwsException gone = assertThrows(AwsException.class, () -> service.get(arn));
        assertEquals(404, gone.getHttpStatus());
        assertEquals("Durable Execution does not exist", gone.getMessage());
    }

    @Test
    void awaitCompletionResolvesWhenTheExecutionCloses() throws Exception {
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(waitStart("w1", 2)));
            return pending();
        });
        invoker.script(event -> succeeded("\"late\""));
        String arn = start("exec-1", "{}", true).getExecutionArn();
        assertFalse(service.awaitCompletion(arn).isDone());

        clock.advance(Duration.ofSeconds(2));
        service.sweep();

        assertEquals("\"late\"", service.awaitCompletion(arn).get(5, TimeUnit.SECONDS).getResult());
    }

    @Test
    void aWaitThatFiresWhileTheHandlerRunsResumesInsteadOfFailing() {
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(waitStart("w1", 1)));
            return pending();
        });
        invoker.script(event -> {
            clock.advance(Duration.ofSeconds(1));
            service.sweep();
            return pending();
        });
        invoker.script(event -> {
            assertEquals("SUCCEEDED", operation(event, "w1").get("Status").asText());
            return succeeded("\"resumed\"");
        });

        String arn = start("exec-1", "{}", false).getExecutionArn();
        service.recoverAfterRestart();

        assertEquals(DurableExecutionStatus.SUCCEEDED, service.get(arn).getStatus());
        assertEquals(3, invoker.events.size());
    }

    @Test
    void executionNamesAreScopedToTheFunction() {
        invoker.script(event -> succeeded("\"a\""));
        invoker.script(event -> succeeded("\"b\""));
        DurableExecution first = start("shared", "{}", true);

        invoker.functionName = "other-fn";
        DurableExecution second = service.start(new StartRequest(ACCOUNT, REGION, "other-fn", "1", "shared", "{\"x\":1}",
                true));

        assertFalse(first.getExecutionArn().equals(second.getExecutionArn()));
        assertEquals("\"b\"", service.get(second.getExecutionArn()).getResult());
    }

    @Test
    void aWakeUpDuringAFailedInvocationDoesNotAddAnInvocationAfterTheRetry() {
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(waitStart("w1", 1)));
            return pending();
        });
        invoker.script(event -> {
            clock.advance(Duration.ofSeconds(1));
            service.sweep();
            return functionError("{\"errorMessage\":\"crash\",\"errorType\":\"E\"}");
        });
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(waitStart("w2", 60)));
            return pending();
        });
        String arn = start("exec-1", "{}", false).getExecutionArn();
        service.recoverAfterRestart();

        service.sweep();

        assertEquals(3, invoker.events.size(), "the retry is the only invocation after the crash");
        assertEquals(DurableExecutionStatus.RUNNING, service.get(arn).getStatus());
    }

    @Test
    void aRestartKeepsTheBackoffOfAPendingRetry() {
        invoker.script(event -> functionError("{\"errorMessage\":\"crash\",\"errorType\":\"E\"}"));
        invoker.script(event -> succeeded("\"ok\""));
        String arn = start("exec-1", "{}", false).getExecutionArn();

        newService(storage, invoker, clock).recoverAfterRestart();
        assertEquals(1, invoker.events.size(), "the retry waits for its backoff");

        clock.advance(Duration.ofSeconds(1));
        service.sweep();
        assertEquals(DurableExecutionStatus.SUCCEEDED, service.get(arn).getStatus());
    }

    @Test
    void aNewInvocationClearsAPendingRetry() {
        invoker.script(event -> functionError("{\"errorMessage\":\"crash\",\"errorType\":\"E\"}"));
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(waitStart("w1", 60)));
            return pending();
        });
        String arn = start("exec-1", "{}", false).getExecutionArn();
        assertNotNull(service.get(arn).getNextInvocationAttemptAt());

        service.sweep();

        assertNull(service.get(arn).getNextInvocationAttemptAt());
        service.sweep();
        assertEquals(2, invoker.events.size(), "no extra invocation after the retry ran");
    }

    @Test
    void aCallbackCompletedFromOutsideReinvokesTheFunctionWithItsResult() {
        invoker.script(event -> {
            CheckpointResult started = checkpoint(event, token(event), List.of(callbackStart("c1", 0, 0)));
            assertNotNull(started.newExecutionState().get(0).getCallbackId());
            return pending();
        });
        invoker.script(event -> {
            JsonNode callback = operation(event, "c1");
            assertEquals("SUCCEEDED", callback.get("Status").asText());
            assertEquals("\"approved\"", callback.at("/CallbackDetails/Result").asText());
            assertEquals("c1", event.get("UpdatedOperationIds").get(0).asText());
            return succeeded("\"done\"");
        });
        String arn = start("exec-1", "{}", false).getExecutionArn();
        String callbackId = service.get(arn).getOperations().get("c1").getCallbackId();

        service.completeCallback(callbackId, ACCOUNT, REGION, true, "\"approved\"", null);

        DurableExecution execution = service.get(arn);
        assertEquals(DurableExecutionStatus.SUCCEEDED, execution.getStatus());
        assertEquals(List.of("ExecutionStarted", "CallbackStarted", "InvocationCompleted", "CallbackSucceeded",
                "InvocationCompleted", "ExecutionSucceeded"), eventTypes(execution));
        assertCallbackClosed(() -> service.completeCallback(callbackId, ACCOUNT, REGION, false, null, null));
        assertCallbackClosed(() -> service.heartbeatCallback(callbackId, ACCOUNT, REGION));
    }

    @Test
    void aFailedCallbackCarriesTheErrorItWasSent() {
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(callbackStart("c1", 0, 0)));
            return pending();
        });
        invoker.script(event -> {
            JsonNode callback = operation(event, "c1");
            assertEquals("FAILED", callback.get("Status").asText());
            assertEquals("Rejected", callback.at("/CallbackDetails/Error/ErrorType").asText());
            return succeeded("\"handled\"");
        });
        String arn = start("exec-1", "{}", false).getExecutionArn();

        service.completeCallback(service.get(arn).getOperations().get("c1").getCallbackId(), ACCOUNT, REGION, false,
                null, DurableErrorObject.of("denied", "Rejected"));

        assertEquals(DurableExecutionStatus.SUCCEEDED, service.get(arn).getStatus());
    }

    @Test
    void aFailureWithoutAnErrorIsRecordedButDoesNotInvokeTheFunction() {
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(callbackStart("c1", 0, 0)));
            return pending();
        });
        String arn = start("exec-1", "{}", false).getExecutionArn();
        String callbackId = service.get(arn).getOperations().get("c1").getCallbackId();

        service.completeCallback(callbackId, ACCOUNT, REGION, false, null, null);

        DurableExecution execution = service.get(arn);
        assertEquals(1, invoker.events.size());
        assertEquals(DurableExecutionStatus.RUNNING, execution.getStatus());
        assertEquals(DurableOperationStatus.FAILED, execution.getOperations().get("c1").getStatus());
        assertEquals("CallbackFailed", eventTypes(execution).get(eventTypes(execution).size() - 1));
        assertCallbackClosed(() -> service.completeCallback(callbackId, ACCOUNT, REGION, true, "\"x\"", null));
    }

    @Test
    void heartbeatsKeepACallbackAliveUntilTheyStop() {
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(callbackStart("c1", 0, 3)));
            return pending();
        });
        invoker.script(event -> {
            assertEquals("Callback.Heartbeat", operation(event, "c1").at("/CallbackDetails/Error/ErrorType").asText());
            return succeeded("\"gave up\"");
        });
        String arn = start("exec-1", "{}", false).getExecutionArn();
        String callbackId = service.get(arn).getOperations().get("c1").getCallbackId();

        clock.advance(Duration.ofSeconds(2));
        service.heartbeatCallback(callbackId, ACCOUNT, REGION);
        clock.advance(Duration.ofSeconds(2));
        service.sweep();
        assertEquals(DurableOperationStatus.STARTED, service.get(arn).getOperations().get("c1").getStatus());

        clock.advance(Duration.ofSeconds(1));
        assertCallbackClosed(() -> service.heartbeatCallback(callbackId, ACCOUNT, REGION));
        service.sweep();

        assertEquals(DurableOperationStatus.TIMED_OUT, service.get(arn).getOperations().get("c1").getStatus());
        assertEquals(DurableExecutionStatus.SUCCEEDED, service.get(arn).getStatus());
    }

    @Test
    void callbackIdsThatMatchNoOpenCallbackAreRejected() {
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(callbackStart("c1", 0, 0)));
            return pending();
        });
        String arn = start("exec-1", "{}", false).getExecutionArn();
        String callbackId = service.get(arn).getOperations().get("c1").getCallbackId();

        for (String malformed : List.of("QUJD", "not-valid!")) {
            AwsException rejected = assertThrows(AwsException.class,
                    () -> service.heartbeatCallback(malformed, ACCOUNT, REGION));
            assertEquals("InvalidParameterValueException", rejected.getErrorCode());
            assertEquals("Invalid callback id", rejected.getMessage());
        }
        assertCallbackClosed(() -> service.heartbeatCallback(callbackId, ACCOUNT, "eu-west-1"));
        assertCallbackClosed(() -> service.heartbeatCallback(callbackId, "111111111111", REGION));

        service.stop(arn, null);
        assertCallbackClosed(() -> service.completeCallback(callbackId, ACCOUNT, REGION, true, "\"late\"", null));
    }

    @Test
    void aChainedInvokeOfAPlainFunctionHandsItsResultToTheNextInvocation() {
        invoker.plainFunctions.put("plain-fn", event -> handlerResponse("{\"echo\":" + event + "}"));
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(chainedStart("i1", "plain-fn", "{\"a\":1}")));
            return pending();
        });
        invoker.script(event -> {
            JsonNode invoke = operation(event, "i1");
            assertEquals("SUCCEEDED", invoke.get("Status").asText());
            assertEquals("{\"echo\":{\"a\":1}}", invoke.at("/ChainedInvokeDetails/Result").asText());
            return succeeded("\"done\"");
        });

        DurableExecution execution = service.get(start("exec-1", "{}", false).getExecutionArn());

        assertEquals(DurableExecutionStatus.SUCCEEDED, execution.getStatus());
        DurableHistoryEvent started = event(execution, "ChainedInvokeStarted");
        assertEquals(Map.of("FunctionName", "plain-fn", "Input", Map.of("Payload", "{\"a\":1}", "Truncated", false),
                "ExecutedVersion", "$LATEST"), started.getDetails().get("ChainedInvokeStartedDetails"));
        assertTrue(eventTypes(execution).contains("ChainedInvokeSucceeded"));
    }

    @Test
    void aChainedInvokeOfAPlainFunctionFailsWithItsErrorOrAnOversizedOutput() {
        invoker.plainFunctions.put("raising-fn",
                event -> functionError("{\"errorMessage\":\"child exploded\",\"errorType\":\"ValueError\"}"));
        invoker.plainFunctions.put("chatty-fn", event -> handlerResponse("\"" + "x".repeat(1024 * 1024) + "\""));
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(chainedStart("raise", "raising-fn", null),
                    chainedStart("chatty", "chatty-fn", null)));
            return pending();
        });
        invoker.script(event -> {
            assertEquals("ValueError", operation(event, "raise").at("/ChainedInvokeDetails/Error/ErrorType").asText());
            JsonNode chatty = operation(event, "chatty");
            assertEquals("FAILED", chatty.get("Status").asText());
            assertEquals("CHAINED_INVOKE output payload size must be less than or equal to 1048576 bytes.",
                    chatty.at("/ChainedInvokeDetails/Error/ErrorMessage").asText());
            return succeeded("\"handled\"");
        });

        assertEquals(DurableExecutionStatus.SUCCEEDED, service.get(start("exec-1", "{}", false).getExecutionArn())
                .getStatus());
    }

    @Test
    void aChainedInvokeThatCannotStartFailsAtOnce() {
        invoker.childScript("child-fn", event -> succeeded("\"never\""));
        invoker.script(event -> {
            CheckpointResult result = checkpoint(event, token(event), List.of(chainedStart("missing", "missing-fn", null),
                    chainedStart("unqualified", "child-fn", null)));
            assertEquals(List.of(DurableOperationStatus.FAILED, DurableOperationStatus.FAILED),
                    result.newExecutionState().stream().map(DurableOperation::getStatus).toList());
            return pending();
        });
        invoker.script(event -> succeeded("\"handled\""));

        DurableExecution execution = service.get(start("exec-1", "{}", false).getExecutionArn());

        DurableOperation missing = execution.getOperations().get("missing");
        assertEquals("ResourceNotFoundException", missing.getError().getErrorType());
        assertEquals("Function not found: arn:aws:lambda:us-east-1:000000000000:function:missing-fn:$LATEST",
                missing.getError().getErrorMessage());
        assertEquals("You cannot invoke a durable function using an unqualified ARN.",
                execution.getOperations().get("unqualified").getError().getErrorMessage());
        assertEquals(Map.of("FunctionName", "missing-fn"),
                event(execution, "ChainedInvokeStarted").getDetails().get("ChainedInvokeStartedDetails"));
        assertEquals(DurableExecutionStatus.SUCCEEDED, execution.getStatus());
    }

    @Test
    void aDurableChildReportsHowItClosedToTheParentOperation() {
        invoker.childScript("child-fn", event -> succeeded("\"child result\""));
        invoker.childScript("child-fn", event -> handlerResponse(
                "{\"Status\":\"FAILED\",\"Error\":{\"ErrorMessage\":\"child failed\",\"ErrorType\":\"ChildError\"}}"));
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(chainedStart("ok", "child-fn:1", "{\"x\":1}"),
                    chainedStart("bad", "child-fn:1", null)));
            return pending();
        });
        invoker.script(event -> {
            assertEquals("\"child result\"", operation(event, "ok").at("/ChainedInvokeDetails/Result").asText());
            assertEquals("ChildError", operation(event, "bad").at("/ChainedInvokeDetails/Error/ErrorType").asText());
            return succeeded("\"done\"");
        });

        DurableExecution parent = service.get(start("exec-1", "{}", false).getExecutionArn());

        assertEquals(DurableExecutionStatus.SUCCEEDED, parent.getStatus());
        String childArn = parent.getOperations().get("ok").getChildExecutionArn();
        DurableExecution child = service.get(childArn);
        assertEquals("{\"x\":1}", child.getInputPayload());
        assertNull(service.get(parent.getOperations().get("bad").getChildExecutionArn()).getInputPayload(),
                "a missing Payload starts the child with no input");
        assertEquals(parent.getExecutionArn(), child.getParentExecutionArn());
        assertEquals(childArn, ((Map<?, ?>) event(parent, "ChainedInvokeStarted").getDetails()
                .get("ChainedInvokeStartedDetails")).get("DurableExecutionArn"));
    }

    @Test
    void aDurableChildThatTimesOutOrIsStoppedClosesTheParentOperationTheSameWay() {
        invoker.childExecutionTimeoutSeconds = 60;
        invoker.childScript("child-fn", event -> {
            checkpoint(event, token(event), List.of(waitStart("w1", 600)));
            return pending();
        });
        invoker.childScript("child-fn", event -> {
            checkpoint(event, token(event), List.of(waitStart("w1", 600)));
            return pending();
        });
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(chainedStart("slow", "child-fn:1", null),
                    chainedStart("stopped", "child-fn:1", null)));
            return pending();
        });
        invoker.script(event -> {
            assertEquals("STOPPED", operation(event, "stopped").get("Status").asText());
            assertEquals("stopped by test", operation(event, "stopped").at("/ChainedInvokeDetails/Error/ErrorMessage")
                    .asText());
            return pending();
        });
        invoker.script(event -> {
            JsonNode slow = operation(event, "slow");
            assertEquals("TIMED_OUT", slow.get("Status").asText());
            assertEquals("CHAINED_INVOKE timed out after 60 seconds.",
                    slow.at("/ChainedInvokeDetails/Error/ErrorMessage").asText());
            assertEquals("ChainedInvoke.Timeout", slow.at("/ChainedInvokeDetails/Error/ErrorType").asText());
            return succeeded("\"done\"");
        });
        String parentArn = start("exec-1", "{}", false).getExecutionArn();

        service.stop(service.get(parentArn).getOperations().get("stopped").getChildExecutionArn(),
                DurableErrorObject.of("stopped by test", "Test"));
        assertEquals(2, invoker.events.size(), "the stopped child woke the parent");

        clock.advance(Duration.ofSeconds(59));
        service.sweep();
        assertEquals(2, invoker.events.size(), "the child has not timed out yet");
        clock.advance(Duration.ofSeconds(1));
        service.sweep();

        assertEquals(DurableExecutionStatus.SUCCEEDED, service.get(parentArn).getStatus());
        assertEquals(3, invoker.events.size());
    }

    @Test
    void aChainedInvokeInTheBatchThatClosesTheExecutionStillRuns() {
        List<String> received = new ArrayList<>();
        invoker.plainFunctions.put("plain-fn", event -> {
            received.add(event.toString());
            return handlerResponse("\"ignored\"");
        });
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(chainedStart("i1", "plain-fn", "{\"n\":1}"),
                    executionSucceed("\"closed\"")));
            return succeeded("");
        });

        DurableExecution execution = service.get(start("exec-1", "{}", false).getExecutionArn());

        assertEquals(List.of("{\"n\":1}"), received);
        assertEquals("\"closed\"", execution.getResult());
        assertEquals(List.of("ExecutionStarted", "InvocationCompleted", "ChainedInvokeStarted", "ExecutionSucceeded"),
                eventTypes(execution));
        assertEquals(1, invoker.events.size());
    }

    @Test
    void aTargetThatCannotBeInvokedStartsInBatchOrderAndFailsBeforeTheInvocationEnds() {
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(step("s1", DurableOperationAction.START, null, null),
                    chainedStart("i1", "missing-fn", null), step("s2", DurableOperationAction.START, null, null),
                    executionSucceed("\"closed\"")));
            return succeeded("");
        });

        DurableExecution execution = service.get(start("exec-1", "{}", false).getExecutionArn());

        assertEquals(List.of("ExecutionStarted", "StepStarted", "ChainedInvokeStarted", "StepStarted",
                "ChainedInvokeFailed", "InvocationCompleted", "ExecutionSucceeded"), eventTypes(execution));
        assertEquals(DurableOperationStatus.FAILED, execution.getOperations().get("i1").getStatus());
    }

    @Test
    void stoppingTheParentLeavesADurableChildRunning() {
        invoker.childScript("child-fn", event -> {
            checkpoint(event, token(event), List.of(waitStart("w1", 600)));
            return pending();
        });
        invoker.script(event -> {
            checkpoint(event, token(event), List.of(chainedStart("i1", "child-fn:1", null)));
            return pending();
        });
        String parentArn = start("exec-1", "{}", false).getExecutionArn();
        String childArn = service.get(parentArn).getOperations().get("i1").getChildExecutionArn();

        service.stop(parentArn, null);

        assertEquals(DurableExecutionStatus.RUNNING, service.get(childArn).getStatus());
        assertFalse(eventTypes(service.get(parentArn)).contains("ChainedInvokeStopped"));
    }

    // ──────────────────────────── helpers ────────────────────────────

    private static DurableHistoryEvent event(DurableExecution execution, String eventType) {
        return execution.getHistory().stream().filter(event -> eventType.equals(event.getEventType())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + eventType + " in " + eventTypes(execution)));
    }

    private static DurableOperationUpdate chainedStart(String id, String functionName, String payload) {
        return new DurableOperationUpdate(id, null, null, DurableOperationType.CHAINED_INVOKE, "ChainedInvoke",
                DurableOperationAction.START, payload, null, null, null, null, null, null, functionName, null);
    }

    private static void assertCallbackClosed(Executable call) {
        AwsException rejected = assertThrows(AwsException.class, call);
        assertEquals("CallbackTimeoutException", rejected.getErrorCode());
        assertEquals("The callback is either timed out or already completed", rejected.getMessage());
        assertEquals(400, rejected.getHttpStatus());
    }

    private static DurableExecutionService newService(InMemoryStorageFactory storage, ScriptedInvoker invoker,
                                                      MutableClock clock) {
        return new DurableExecutionService(storage, MAPPER, clock, invoker, Runnable::run, Duration.ZERO);
    }

    private DurableExecution start(String name, String payload, boolean synchronous) {
        return service.start(new StartRequest(ACCOUNT, REGION, FUNCTION, "1", name, payload, synchronous));
    }

    private CheckpointResult checkpoint(JsonNode event, String token, List<DurableOperationUpdate> updates) {
        return service.checkpoint(event.get("DurableExecutionArn").asText(), token, null, updates);
    }

    private static ListRequest listRequest(Set<DurableExecutionStatus> statuses, String name, Integer maxItems,
                                           String marker) {
        return new ListRequest(ACCOUNT, REGION, FUNCTION, null, name, statuses, null, null, false, maxItems, marker);
    }

    private static String token(JsonNode event) {
        return event.get("CheckpointToken").asText();
    }

    private static JsonNode operation(JsonNode event, String id) {
        for (JsonNode operation : event.get("InitialExecutionState").get("Operations")) {
            if (id.equals(operation.get("Id").asText())) {
                return operation;
            }
        }
        throw new AssertionError("no operation " + id + " in " + event);
    }

    private static List<String> eventTypes(DurableExecution execution) {
        return execution.getHistory().stream().map(DurableHistoryEvent::getEventType).toList();
    }

    private static DurableOperationUpdate step(String id, DurableOperationAction action, String payload,
                                               DurableErrorObject error) {
        return new DurableOperationUpdate(id, null, null, DurableOperationType.STEP, "Step", action, payload, error,
                null, null, null, null, null, null, null);
    }

    private static DurableOperationUpdate stepRetry(String id, int delaySeconds, DurableErrorObject error) {
        return new DurableOperationUpdate(id, null, null, DurableOperationType.STEP, "Step",
                DurableOperationAction.RETRY, null, error, delaySeconds, null, null, null, null, null, null);
    }

    private static DurableOperationUpdate callbackStart(String id, int timeoutSeconds, int heartbeatSeconds) {
        return new DurableOperationUpdate(id, null, null, DurableOperationType.CALLBACK, "Callback",
                DurableOperationAction.START, null, null, null, null, null, timeoutSeconds, heartbeatSeconds, null, null);
    }

    private static DurableOperationUpdate waitStart(String id, int seconds) {
        return new DurableOperationUpdate(id, null, null, DurableOperationType.WAIT, "Wait",
                DurableOperationAction.START, null, null, null, seconds, null, null, null, null, null);
    }

    private static DurableOperationUpdate executionSucceed(String payload) {
        return new DurableOperationUpdate("execution-result", null, null, DurableOperationType.EXECUTION, null,
                DurableOperationAction.SUCCEED, payload, null, null, null, null, null, null, null, null);
    }

    private static DurableFunctionInvoker.DurableInvocationResult succeeded(String result) {
        return handlerResponse("{\"Status\":\"SUCCEEDED\",\"Result\":" + MAPPER.valueToTree(result) + "}");
    }

    private static DurableFunctionInvoker.DurableInvocationResult pending() {
        return handlerResponse("{\"Status\":\"PENDING\"}");
    }

    private static DurableFunctionInvoker.DurableInvocationResult handlerResponse(String json) {
        return new DurableFunctionInvoker.DurableInvocationResult("req", json.getBytes(StandardCharsets.UTF_8), null);
    }

    private static DurableFunctionInvoker.DurableInvocationResult functionError(String json) {
        return new DurableFunctionInvoker.DurableInvocationResult("req", json.getBytes(StandardCharsets.UTF_8),
                "Unhandled");
    }

    /** Plays one script per invocation; a missing script fails the execution so the test shows it. */
    private static final class ScriptedInvoker implements DurableFunctionInvoker {

        final Deque<Function<JsonNode, DurableInvocationResult>> scripts = new ArrayDeque<>();
        final List<JsonNode> events = new ArrayList<>();
        final List<String> requestIds = new ArrayList<>();
        /** Durable functions other than the one under test, each with its own scripts. */
        final Map<String, Deque<Function<JsonNode, DurableInvocationResult>>> childScripts = new HashMap<>();
        /** Plain functions a chained invoke may call, answering the raw payload. */
        final Map<String, Function<JsonNode, DurableInvocationResult>> plainFunctions = new HashMap<>();
        int executionTimeoutSeconds = 3600;
        int childExecutionTimeoutSeconds = 3600;
        String functionName = FUNCTION;
        int retentionPeriodInDays = 7;

        void script(Function<JsonNode, DurableInvocationResult> script) {
            scripts.add(script);
        }

        void childScript(String function, Function<JsonNode, DurableInvocationResult> script) {
            childScripts.computeIfAbsent(function, ignored -> new ArrayDeque<>()).add(script);
        }

        @Override
        public ResolvedDurableTarget resolve(String accountId, String region, String functionName, String qualifier) {
            String version = qualifier == null ? "$LATEST" : qualifier;
            if (plainFunctions.containsKey(functionName)) {
                return new ResolvedDurableTarget(accountId, region, functionName,
                        "arn:aws:lambda:us-east-1:000000000000:function:" + functionName, version, false, 0, 0);
            }
            String name = FUNCTION.equals(functionName) ? this.functionName : functionName;
            if (!name.equals(this.functionName) && !childScripts.containsKey(name)) {
                throw new AwsException("ResourceNotFoundException", "Function not found: " + name, 404);
            }
            String durableVersion = qualifier == null ? "1" : qualifier;
            return new ResolvedDurableTarget(accountId, region, name,
                    "arn:aws:lambda:us-east-1:000000000000:function:" + name + ":" + durableVersion, durableVersion,
                    true, name.equals(this.functionName) ? executionTimeoutSeconds : childExecutionTimeoutSeconds,
                    retentionPeriodInDays);
        }

        @Override
        public DurableInvocationResult invoke(ResolvedDurableTarget target, byte[] payload, String requestId) {
            JsonNode event;
            try {
                event = MAPPER.readTree(payload);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (!target.durable()) {
                return plainFunctions.get(target.functionName()).apply(event);
            }
            Deque<Function<JsonNode, DurableInvocationResult>> queue = childScripts.get(target.functionName());
            if (queue != null) {
                Function<JsonNode, DurableInvocationResult> script = queue.poll();
                return script != null ? script.apply(event) : pending();
            }
            events.add(event);
            requestIds.add(requestId);
            Function<JsonNode, DurableInvocationResult> script = scripts.poll();
            if (script == null) {
                return handlerResponse("{\"Status\":\"FAILED\",\"Error\":{\"ErrorMessage\":\"no script for "
                        + "invocation " + events.size() + "\",\"ErrorType\":\"TestError\"}}");
            }
            return script.apply(event);
        }
    }
}

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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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

    // ──────────────────────────── helpers ────────────────────────────

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
                null, null, null);
    }

    private static DurableOperationUpdate stepRetry(String id, int delaySeconds, DurableErrorObject error) {
        return new DurableOperationUpdate(id, null, null, DurableOperationType.STEP, "Step",
                DurableOperationAction.RETRY, null, error, delaySeconds, null, null);
    }

    private static DurableOperationUpdate waitStart(String id, int seconds) {
        return new DurableOperationUpdate(id, null, null, DurableOperationType.WAIT, "Wait",
                DurableOperationAction.START, null, null, null, seconds, null);
    }

    private static DurableOperationUpdate executionSucceed(String payload) {
        return new DurableOperationUpdate("execution-result", null, null, DurableOperationType.EXECUTION, null,
                DurableOperationAction.SUCCEED, payload, null, null, null, null);
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
        int executionTimeoutSeconds = 3600;
        String functionName = FUNCTION;
        int retentionPeriodInDays = 7;

        void script(Function<JsonNode, DurableInvocationResult> script) {
            scripts.add(script);
        }

        @Override
        public ResolvedDurableTarget resolve(String accountId, String region, String functionName, String qualifier) {
            String version = qualifier == null ? "1" : qualifier;
            return new ResolvedDurableTarget(accountId, region, this.functionName,
                    "arn:aws:lambda:us-east-1:000000000000:function:" + this.functionName + ":" + version, version, true,
                    executionTimeoutSeconds, retentionPeriodInDays);
        }

        @Override
        public DurableInvocationResult invoke(ResolvedDurableTarget target, byte[] payload) {
            JsonNode event;
            try {
                event = MAPPER.readTree(payload);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            events.add(event);
            Function<JsonNode, DurableInvocationResult> script = scripts.poll();
            if (script == null) {
                return handlerResponse("{\"Status\":\"FAILED\",\"Error\":{\"ErrorMessage\":\"no script for "
                        + "invocation " + events.size() + "\",\"ErrorType\":\"TestError\"}}");
            }
            return script.apply(event);
        }
    }
}

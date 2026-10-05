package io.github.hectorvent.floci.services.lambda.durable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WriteProfile;
import io.github.hectorvent.floci.services.lambda.durable.DurableExecutionService.StartRequest;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecution;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecutionStatus;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableHistoryEvent;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationAction;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationType;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationUpdate;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A restart reloads executions from the JSON file, not from shared objects, so this proves that
 * operations, history, deadlines and the invocation lane survive serialization.
 */
class DurableExecutionServicePersistenceTest {

    private static final String ACCOUNT = "000000000000";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path directory;

    private final MutableClock clock = new MutableClock();

    @Test
    void aRunningExecutionResumesFromDiskAfterARestart() {
        ScriptedInvoker before = new ScriptedInvoker();
        PersistentStorageFactory firstStorage = new PersistentStorageFactory(directory);
        DurableExecutionService first = newService(firstStorage, before);
        before.script((service, event) -> {
            service.checkpoint(arn(event), token(event), null, List.of(
                    update("s1", DurableOperationType.STEP, DurableOperationAction.START, null, null),
                    update("s1", DurableOperationType.STEP, DurableOperationAction.SUCCEED, "\"saved\"", null),
                    update("w1", DurableOperationType.WAIT, DurableOperationAction.START, null, 5)));
            return "{\"Status\":\"PENDING\"}";
        });
        String executionArn = first.start(new StartRequest(ACCOUNT, "us-east-1", "durable-fn", "1", "exec-1", "{\"a\":1}",
                false)).getExecutionArn();
        String oldToken = token(before.events.get(0));
        firstStorage.flushAll();

        ScriptedInvoker after = new ScriptedInvoker();
        DurableExecutionService restarted = newService(new PersistentStorageFactory(directory), after);
        after.script((service, event) -> {
            assertEquals("SUCCEEDED", operation(event, "s1").get("Status").asText());
            assertEquals("\"saved\"", operation(event, "s1").at("/StepDetails/Result").asText());
            assertEquals("STARTED", operation(event, "w1").get("Status").asText());
            return "{\"Status\":\"PENDING\"}";
        });
        after.script((service, event) -> {
            assertEquals("SUCCEEDED", operation(event, "w1").get("Status").asText());
            return "{\"Status\":\"SUCCEEDED\",\"Result\":\"\\\"done\\\"\"}";
        });
        restarted.recoverAfterRestart();

        assertThrows(AwsException.class, () -> restarted.checkpoint(executionArn, oldToken, null, List.of()));
        clock.advance(Duration.ofSeconds(5));
        restarted.sweep();

        DurableExecution execution = restarted.get(executionArn);
        assertEquals(DurableExecutionStatus.SUCCEEDED, execution.getStatus());
        assertEquals("\"done\"", execution.getResult());
        assertEquals("{\"a\":1}", execution.getInputPayload());
        assertEquals(List.of("ExecutionStarted", "StepStarted", "StepSucceeded", "WaitStarted", "InvocationCompleted",
                        "InvocationCompleted", "WaitSucceeded", "InvocationCompleted", "ExecutionSucceeded"),
                execution.getHistory().stream().map(DurableHistoryEvent::getEventType).toList());
    }

    @Test
    void aPendingCallbackKeepsItsIdAndDeadlineAcrossARestart() {
        ScriptedInvoker before = new ScriptedInvoker();
        PersistentStorageFactory firstStorage = new PersistentStorageFactory(directory);
        DurableExecutionService first = newService(firstStorage, before);
        before.script((service, event) -> {
            service.checkpoint(arn(event), token(event), null, List.of(callbackStart("approve", 0),
                    callbackStart("expire", 5)));
            return "{\"Status\":\"PENDING\"}";
        });
        String executionArn = first.start(new StartRequest(ACCOUNT, "us-east-1", "durable-fn", "1", "exec-1", "{}",
                false)).getExecutionArn();
        String callbackId = first.get(executionArn).getOperations().get("approve").getCallbackId();
        firstStorage.flushAll();

        ScriptedInvoker after = new ScriptedInvoker();
        DurableExecutionService restarted = newService(new PersistentStorageFactory(directory), after);
        after.script((service, event) -> "{\"Status\":\"PENDING\"}");
        after.script((service, event) -> {
            assertEquals("SUCCEEDED", operation(event, "approve").get("Status").asText());
            assertEquals("STARTED", operation(event, "expire").get("Status").asText());
            return "{\"Status\":\"PENDING\"}";
        });
        after.script((service, event) -> {
            assertEquals("Callback.Timeout", operation(event, "expire").at("/CallbackDetails/Error/ErrorType").asText());
            return "{\"Status\":\"SUCCEEDED\",\"Result\":\"\\\"done\\\"\"}";
        });
        restarted.recoverAfterRestart();

        restarted.completeCallback(callbackId, ACCOUNT, "us-east-1", true, "\"yes\"", null);
        clock.advance(Duration.ofSeconds(5));
        restarted.sweep();

        assertEquals(DurableExecutionStatus.SUCCEEDED, restarted.get(executionArn).getStatus());
        assertEquals(3, after.events.size());
    }

    private DurableExecutionService newService(StorageFactory storage, ScriptedInvoker invoker) {
        DurableExecutionService service = new DurableExecutionService(storage, MAPPER, clock, invoker, Runnable::run,
                Duration.ZERO);
        invoker.service = service;
        return service;
    }

    private static DurableOperationUpdate update(String id, DurableOperationType type, DurableOperationAction action,
                                                 String payload, Integer waitSeconds) {
        return new DurableOperationUpdate(id, null, null, type, null, action, payload, null, null, waitSeconds, null, null, null);
    }

    private static DurableOperationUpdate callbackStart(String id, int timeoutSeconds) {
        return new DurableOperationUpdate(id, null, null, DurableOperationType.CALLBACK, "Callback",
                DurableOperationAction.START, null, null, null, null, null, timeoutSeconds, null);
    }

    private static String arn(JsonNode event) {
        return event.get("DurableExecutionArn").asText();
    }

    private static String token(JsonNode event) {
        return event.get("CheckpointToken").asText();
    }

    private static JsonNode operation(JsonNode event, String id) {
        for (JsonNode operation : event.at("/InitialExecutionState/Operations")) {
            if (id.equals(operation.get("Id").asText())) {
                return operation;
            }
        }
        throw new AssertionError("no operation " + id);
    }

    private interface Script {
        String run(DurableExecutionService service, JsonNode event);
    }

    private static final class ScriptedInvoker implements DurableFunctionInvoker {

        final Deque<Script> scripts = new ArrayDeque<>();
        final List<JsonNode> events = new ArrayList<>();
        DurableExecutionService service;

        void script(Script script) {
            scripts.add(script);
        }

        @Override
        public ResolvedDurableTarget resolve(String accountId, String region, String functionName, String qualifier) {
            return new ResolvedDurableTarget(accountId, region, functionName,
                    "arn:aws:lambda:us-east-1:000000000000:function:" + functionName + ":1", "1", true, 3600, 7);
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
            Script script = scripts.poll();
            String body = script == null ? "{\"Status\":\"FAILED\",\"Error\":{\"ErrorMessage\":\"no script\"}}"
                    : script.run(service, event);
            return new DurableInvocationResult("req", body.getBytes(StandardCharsets.UTF_8), null);
        }
    }

    /** Each instance reads the JSON file from disk, as a restarted Floci does. */
    private static final class PersistentStorageFactory extends StorageFactory {

        private final Path directory;
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        PersistentStorageFactory(Path directory) {
            super(null, null);
            this.directory = directory;
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                        TypeReference<Map<String, V>> typeReference) {
            return create(serviceName, fileName, typeReference, WriteProfile.DEFAULT);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                        TypeReference<Map<String, V>> typeReference,
                                                        WriteProfile profile) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> {
                PersistentStorage<String, V> storage = new PersistentStorage<>(directory.resolve(fileName),
                        typeReference);
                storage.load();
                return new AccountAwareStorageBackend<>(storage, null, ACCOUNT);
            });
        }

        @Override
        public void flushAll() {
            stores.values().forEach(StorageBackend::flush);
        }
    }
}

package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.stepfunctions.model.Activity;
import io.github.hectorvent.floci.services.stepfunctions.model.ActivityTask;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.MockedTestCase;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;

/** Verifies executions abandoned by a restart reach a terminal status when the emulator comes back. */
class StepFunctionsServicePersistenceTest {

    @TempDir
    Path tempDir;

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "222222222222";
    private static final String EXECUTION_ARN =
            "arn:aws:states:us-east-1:000000000000:execution:TestStateMachine:HelloWorld";
    private static final String OTHER_ACCOUNT_EXECUTION_ARN =
            "arn:aws:states:us-east-1:222222222222:execution:TestStateMachine:HelloWorld";
    private static final String STATE_MACHINE_ARN =
            "arn:aws:states:us-east-1:000000000000:stateMachine:TestStateMachine";
    private static final double EPOCH_SECONDS_2023_11_14 = 1700000000.0;
    private static final double EPOCH_SECONDS_2100_01_01 = 4102444800.0;

    private final AslExecutor aslExecutor = Mockito.mock(AslExecutor.class);
    private final SfnMockLoader mockLoader = Mockito.mock(SfnMockLoader.class);
    private final RegionResolver regionResolver = Mockito.mock(RegionResolver.class);

    @Test
    void historyCheckpointCallbackRunsAtBoundedIntervals() {
        AtomicInteger lastCheckpoint = new AtomicInteger();
        StepFunctionsService.ExecutionHistory history =
                new StepFunctionsService.ExecutionHistory(eventCount -> {
                    if (eventCount % 100 == 0) {
                        lastCheckpoint.set(eventCount);
                    }
                });

        for (int eventId = 1; eventId <= 99; eventId++) {
            HistoryEvent event = new HistoryEvent();
            event.setId((long) eventId);
            history.add(event);
        }
        assertEquals(0, lastCheckpoint.get());

        HistoryEvent checkpointEvent = new HistoryEvent();
        checkpointEvent.setId(100L);
        history.add(checkpointEvent);

        assertEquals(100, lastCheckpoint.get());
    }

    @Test
    void completedExecutionRetainsExactHistoryAfterReload() {
        PersistentTestStorageFactory storage = new PersistentTestStorageFactory(tempDir);
        StateMachine stateMachine = stateMachine();
        storage.create("stepfunctions", "sfn-state-machines.json",
                new TypeReference<Map<String, StateMachine>>() {})
                .putForAccount(DEFAULT_ACCOUNT, STATE_MACHINE_ARN, stateMachine);
        Mockito.when(regionResolver.buildArn("states", "us-east-1",
                        "execution:TestStateMachine:completed"))
                .thenReturn(EXECUTION_ARN);
        doAnswer(invocation -> {
            Execution execution = invocation.getArgument(1);
            List<HistoryEvent> history = invocation.getArgument(2);
            HistoryEvent succeeded = new HistoryEvent();
            succeeded.setId(2L);
            succeeded.setPreviousEventId(1L);
            succeeded.setType("ExecutionSucceeded");
            succeeded.setDetails(Map.of("output", "{\"result\":true}"));
            history.add(succeeded);
            execution.setStatus("SUCCEEDED");
            execution.setOutput("{\"result\":true}");
            invocation.<java.util.function.BiConsumer<Execution, List<HistoryEvent>>>getArgument(4)
                    .accept(execution, history);
            return null;
        }).when(aslExecutor).executeAsync(any(StateMachine.class), any(Execution.class), anyList(),
                isNull(), any());

        StepFunctionsService beforeRestart = serviceWithStorage(storage);
        Execution started = beforeRestart.startExecution(STATE_MACHINE_ARN, "completed", "{}", "us-east-1");
        List<HistoryEvent> expectedHistory = beforeRestart.getExecutionHistory(started.getExecutionArn());
        storage.flushAll();

        StepFunctionsService afterRestart = serviceWithStorage(new PersistentTestStorageFactory(tempDir));

        assertEquals("SUCCEEDED", afterRestart.describeExecution(EXECUTION_ARN).getStatus());
        assertEquals("{\"result\":true}", afterRestart.describeExecution(EXECUTION_ARN).getOutput());
        assertHistoryEquals(expectedHistory, afterRestart.getExecutionHistory(EXECUTION_ARN));
    }

    @Test
    void waitingExecutionIsAbortedWithHistoryAndOldTaskTokenIsRejectedAfterReload() {
        PersistentTestStorageFactory storage = new PersistentTestStorageFactory(tempDir);
        Mockito.when(regionResolver.buildArn("states", "us-east-1",
                        "execution:TestStateMachine:waiting"))
                .thenReturn("arn:aws:states:us-east-1:000000000000:execution:TestStateMachine:waiting");
        Mockito.when(regionResolver.buildArn("states", "us-east-1", "activity:waiting"))
                .thenReturn("arn:aws:states:us-east-1:000000000000:activity:waiting");
        AtomicReference<StepFunctionsService> serviceReference = new AtomicReference<>();
        Instance<StepFunctionsService> serviceInstance = Mockito.mock(Instance.class);
        Mockito.when(serviceInstance.get()).thenAnswer(ignored -> serviceReference.get());
        RestartableAslExecutor realExecutor = realExecutor(serviceInstance);
        StepFunctionsService beforeRestart = serviceWithStorage(storage, realExecutor);
        serviceReference.set(beforeRestart);
        Activity activity = beforeRestart.createActivity("waiting", "us-east-1", Map.of());
        StateMachine stateMachine = stateMachine(activity.getActivityArn());
        storage.create("stepfunctions", "sfn-state-machines.json",
                new TypeReference<Map<String, StateMachine>>() {})
                .putForAccount(DEFAULT_ACCOUNT, STATE_MACHINE_ARN, stateMachine);
        Execution started = beforeRestart.startExecution(STATE_MACHINE_ARN, "waiting", "{}", "us-east-1");
        ActivityTask task = beforeRestart.getActivityTask(activity.getActivityArn(), "worker");
        String taskToken = task.getTaskToken();
        List<HistoryEvent> expectedHistory = new ArrayList<>(beforeRestart.getExecutionHistory(started.getExecutionArn()));
        storage.flushAll();
        realExecutor.stop();

        try {
            StepFunctionsService afterRestart = serviceWithStorage(new PersistentTestStorageFactory(tempDir));
            afterRestart.abortAbandonedExecutions();

            Execution reloaded = afterRestart.describeExecution(started.getExecutionArn());
            assertEquals("ABORTED", reloaded.getStatus());
            assertNull(reloaded.getOutput());
            assertNull(reloaded.getError());
            assertNull(reloaded.getCause());
            HistoryEvent aborted = new HistoryEvent();
            aborted.setId(expectedHistory.size() + 1L);
            aborted.setPreviousEventId((long) expectedHistory.size());
            aborted.setType("ExecutionAborted");
            aborted.setDetails(Map.of());
            expectedHistory.add(aborted);
            List<HistoryEvent> actualHistory = afterRestart.getExecutionHistory(started.getExecutionArn());
            aborted.setTimestamp(actualHistory.getLast().getTimestamp());
            assertHistoryEquals(expectedHistory, actualHistory);
            assertFalse(afterRestart.sendTaskSuccess(taskToken, "{\"done\":true}"));
        } finally {
            beforeRestart.abortAbandonedExecutions();
            beforeRestart.clear();
            assertTrue(realExecutor.awaitCompletion());
        }
    }

    @Test
    void abandonedRunningExecutionIsAbortedOnRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();
        AccountAwareStorageBackend<Execution> executions = executionStore(storage);
        StepFunctionsService beforeRestart = serviceWithStorage(storage);
        executions.putForAccount(DEFAULT_ACCOUNT, EXECUTION_ARN, running(EXECUTION_ARN));
        assertEquals("RUNNING", beforeRestart.describeExecution(EXECUTION_ARN).getStatus());

        StepFunctionsService afterRestart = serviceWithStorage(storage);
        afterRestart.abortAbandonedExecutions();

        // The shape AWS returns for an execution stopped with no error and no cause: the status is
        // the whole report, and error and cause are left off DescribeExecution and off the event.
        Execution reloaded = afterRestart.describeExecution(EXECUTION_ARN);
        assertEquals("ABORTED", reloaded.getStatus());
        assertNull(reloaded.getError());
        assertNull(reloaded.getCause());
        assertStopDateInEpochSeconds(reloaded.getStopDate());

        List<HistoryEvent> history = afterRestart.getExecutionHistory(EXECUTION_ARN);
        assertEquals(1, history.size());
        HistoryEvent aborted = history.getFirst();
        assertEquals("ExecutionAborted", aborted.getType());
        assertEquals(1L, aborted.getId());
        assertEquals(Long.valueOf(0L), aborted.getPreviousEventId());
        assertEquals(Map.of(), aborted.getDetails());

        verifyNoInteractions(aslExecutor, mockLoader, regionResolver);
    }

    @Test
    void abandonedExecutionOfAnotherAccountStaysInItsOwnAccount() {
        SharedStorageFactory storage = new SharedStorageFactory();
        AccountAwareStorageBackend<Execution> executions = executionStore(storage);
        executions.putForAccount(OTHER_ACCOUNT, OTHER_ACCOUNT_EXECUTION_ARN,
                running(OTHER_ACCOUNT_EXECUTION_ARN));

        List<LogRecord> logRecords = new ArrayList<>();
        Handler collector = collectorInto(logRecords);
        Logger serviceLogger = Logger.getLogger(StepFunctionsService.class.getName());
        serviceLogger.addHandler(collector);
        try {
            serviceWithStorage(storage).abortAbandonedExecutions();
        } finally {
            serviceLogger.removeHandler(collector);
        }

        List<LogRecord> warnings = warningsIn(logRecords);
        assertEquals(1, warnings.size(),
                "expected one WARN record for the sweep, got: " + formattedAll(logRecords));
        assertTrue(formatted(warnings.getFirst()).contains("1"),
                "expected the WARN record to name how many executions were aborted, got: "
                        + formatted(warnings.getFirst()));

        Optional<Execution> owned =
                executions.getForAccount(OTHER_ACCOUNT, OTHER_ACCOUNT_EXECUTION_ARN);
        assertTrue(owned.isPresent());
        assertEquals("ABORTED", owned.get().getStatus());
        assertNull(owned.get().getError());
        assertNull(owned.get().getCause());
        assertStopDateInEpochSeconds(owned.get().getStopDate());
        assertEquals(Optional.empty(),
                executions.getForAccount(DEFAULT_ACCOUNT, OTHER_ACCOUNT_EXECUTION_ARN));

        verifyNoInteractions(aslExecutor, mockLoader, regionResolver);
    }

    @Test
    void terminalExecutionsAreUntouchedAndTheSweepIsIdempotent() {
        SharedStorageFactory storage = new SharedStorageFactory();
        AccountAwareStorageBackend<Execution> executions = executionStore(storage);
        Execution succeeded = running("arn:aws:states:us-east-1:000000000000:execution:"
                + "TestStateMachine:HelloWorldDone");
        succeeded.setStatus("SUCCEEDED");
        succeeded.setOutput("{\"greeting\":\"hello\"}");
        succeeded.setStopDate(1700000000.0);
        executions.putForAccount(DEFAULT_ACCOUNT, succeeded.getExecutionArn(), succeeded);
        executions.putForAccount(DEFAULT_ACCOUNT, EXECUTION_ARN, running(EXECUTION_ARN));

        StepFunctionsService afterRestart = serviceWithStorage(storage);
        afterRestart.abortAbandonedExecutions();
        Double stopDateOfFirstSweep =
                afterRestart.describeExecution(EXECUTION_ARN).getStopDate();

        // Nothing is RUNNING any more, so the second sweep retires nothing and says nothing: a WARN
        // on every clean boot is the noise the repo's logging rule forbids.
        List<LogRecord> logRecords = new ArrayList<>();
        Handler collector = collectorInto(logRecords);
        Logger serviceLogger = Logger.getLogger(StepFunctionsService.class.getName());
        serviceLogger.addHandler(collector);
        try {
            afterRestart.abortAbandonedExecutions();
        } finally {
            serviceLogger.removeHandler(collector);
        }
        assertEquals(List.of(), warningsIn(logRecords),
                "expected no WARN record from a sweep that found nothing RUNNING, got: "
                        + formattedAll(logRecords));

        Execution untouched = afterRestart.describeExecution(succeeded.getExecutionArn());
        assertEquals("SUCCEEDED", untouched.getStatus());
        assertEquals("{\"greeting\":\"hello\"}", untouched.getOutput());
        assertEquals(Double.valueOf(1700000000.0), untouched.getStopDate());
        assertEquals(List.of(), afterRestart.getExecutionHistory(succeeded.getExecutionArn()));

        Execution abandoned = afterRestart.describeExecution(EXECUTION_ARN);
        assertEquals("ABORTED", abandoned.getStatus());
        assertEquals(stopDateOfFirstSweep, abandoned.getStopDate());
        assertEquals(1, afterRestart.getExecutionHistory(EXECUTION_ARN).size());

        verifyNoInteractions(aslExecutor, mockLoader, regionResolver);
    }

    private static Handler collectorInto(List<LogRecord> records) {
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        collector.setLevel(Level.ALL);
        return collector;
    }

    private static List<LogRecord> warningsIn(List<LogRecord> records) {
        // The level is compared by value: under JBoss LogManager the record carries its own WARN
        // instance, not the java.util.logging constant.
        return records.stream()
                .filter(record -> record.getLevel().intValue() == Level.WARNING.intValue())
                .toList();
    }

    private static List<String> formattedAll(List<LogRecord> records) {
        return records.stream().map(StepFunctionsServicePersistenceTest::formatted).toList();
    }

    /**
     * A stopDate DescribeExecution can report: epoch seconds, between 2023-11-14 and 2100-01-01.
     * Both bounds are literals, so a stopDate written in another unit fails here.
     */
    private static void assertStopDateInEpochSeconds(Double stopDate) {
        assertNotNull(stopDate);
        assertTrue(stopDate > EPOCH_SECONDS_2023_11_14 && stopDate < EPOCH_SECONDS_2100_01_01,
                "stopDate is not a plausible epoch-seconds instant: " + stopDate);
    }

    /** The record as a reader sees it, whether the handler receives it formatted or as a pattern. */
    private static String formatted(LogRecord record) {
        Object[] parameters = record.getParameters();
        if (parameters == null || parameters.length == 0) {
            return String.valueOf(record.getMessage());
        }
        return MessageFormat.format(record.getMessage(), parameters);
    }

    private static Execution running(String executionArn) {
        Execution execution = new Execution();
        execution.setExecutionArn(executionArn);
        execution.setStateMachineArn(STATE_MACHINE_ARN);
        execution.setName("HelloWorld");
        execution.setInput("{}");
        execution.setStatus("RUNNING");
        return execution;
    }

    private static StateMachine stateMachine() {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setStateMachineArn(STATE_MACHINE_ARN);
        stateMachine.setName("TestStateMachine");
        stateMachine.setRoleArn("arn:aws:iam::000000000000:role/test-role");
        stateMachine.setType("STANDARD");
        stateMachine.setDefinition("{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"End\":true}}}");
        return stateMachine;
    }

    private static StateMachine stateMachine(String activityArn) {
        StateMachine stateMachine = stateMachine();
        stateMachine.setDefinition("{\"StartAt\":\"Wait\",\"States\":{\"Wait\":{\"Type\":\"Task\",\"Resource\":\""
                + activityArn + ".waitForTaskToken\",\"End\":true}}}");
        return stateMachine;
    }

    private static RestartableAslExecutor realExecutor(Instance<StepFunctionsService> serviceInstance) {
        return new RestartableAslExecutor(serviceInstance);
    }

    private static final class RestartableAslExecutor extends AslExecutor {
        private final CountDownLatch completion = new CountDownLatch(1);

        private RestartableAslExecutor(Instance<StepFunctionsService> serviceInstance) {
            super(null, null, null, null, null, null, null, null, null, null,
                null, null, null, new ObjectMapper(), null, serviceInstance, null, null, null);
        }

        private boolean awaitCompletion() {
            try {
                return completion.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public void executeAsync(StateMachine stateMachine, Execution execution,
                                 List<HistoryEvent> history, MockedTestCase mockedTestCase,
                                 java.util.function.BiConsumer<Execution, List<HistoryEvent>> onUpdate) {
            super.executeAsync(stateMachine, execution, history, mockedTestCase,
                    (updatedExecution, updatedHistory) -> {
                        try {
                            onUpdate.accept(updatedExecution, updatedHistory);
                        } finally {
                            completion.countDown();
                        }
                    });
        }

        @Override
        void stop() {
            // Keep the waiting worker alive until the test has reloaded the persisted snapshot.
        }
    }

    private static void assertHistoryEquals(List<HistoryEvent> expected, List<HistoryEvent> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            HistoryEvent expectedEvent = expected.get(index);
            HistoryEvent actualEvent = actual.get(index);
            assertEquals(expectedEvent.getId(), actualEvent.getId());
            assertEquals(expectedEvent.getTimestamp(), actualEvent.getTimestamp());
            assertEquals(expectedEvent.getType(), actualEvent.getType());
            assertEquals(expectedEvent.getPreviousEventId(), actualEvent.getPreviousEventId());
            assertEquals(expectedEvent.getDetails(), actualEvent.getDetails());
        }
    }

    private StepFunctionsService serviceWithStorage(StorageFactory storage) {
        return serviceWithStorage(storage, aslExecutor);
    }

    private StepFunctionsService serviceWithStorage(StorageFactory storage, AslExecutor executor) {
        return new StepFunctionsService(storage, regionResolver, executor,
                new ObjectMapper(), mockLoader);
    }

    private static AccountAwareStorageBackend<Execution> executionStore(StorageFactory storage) {
        return storage.create("stepfunctions", "sfn-executions.json",
                new TypeReference<Map<String, Execution>>() {});
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                       String fileName,
                                                       TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(
                    fileName, ignored -> AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT));
        }
    }

    private static final class PersistentTestStorageFactory extends StorageFactory {
        private final Path directory;
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private PersistentTestStorageFactory(Path directory) {
            super(null, null);
            this.directory = directory;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                       String fileName,
                                                       TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> {
                PersistentStorage<String, V> storage = new PersistentStorage<>(
                        directory.resolve(fileName), typeReference);
                storage.load();
                return new AccountAwareStorageBackend<>(storage, null, DEFAULT_ACCOUNT);
            });
        }

        @Override
        public void flushAll() {
            stores.values().forEach(StorageBackend::flush);
        }
    }
}

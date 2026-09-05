package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

/** Verifies executions abandoned by a restart reach a terminal status when the emulator comes back. */
class StepFunctionsServicePersistenceTest {

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

    private StepFunctionsService serviceWithStorage(StorageFactory storage) {
        return new StepFunctionsService(storage, regionResolver, aslExecutor,
                new ObjectMapper(), mockLoader);
    }

    private static AccountAwareStorageBackend<Execution> executionStore(SharedStorageFactory storage) {
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
}

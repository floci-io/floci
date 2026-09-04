package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.stepfunctions.model.Activity;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EXPRESS StartExecution name reuse for {@link StepFunctionsService}, over a real service with
 * in-memory stores and a mocked {@link AslExecutor}. AWS lets an EXPRESS name be reused immediately:
 * every start is its own concurrent execution with its own ARN, so a same-name start must never
 * conflict and must never overwrite an earlier execution's record (the data-loss regression this
 * branch is named for).
 *
 * <p>CI-only: {@code mock(AslExecutor.class)} forces AslExecutor to load, which needs Vert.x (absent
 * in the offline sandbox), as in {@code StepFunctionsServiceStartExecutionRaceTest}. The STANDARD
 * idempotency/conflict decision stays in {@code StepFunctionsServiceStartExecutionIdempotencyTest}.
 */
class StepFunctionsServiceStartExecutionExpressTest {

    private static final String SM_ARN = "arn:aws:states:us-east-1:000000000000:stateMachine:test-sm";
    private static final String REGION = "us-east-1";
    private static final String EXPRESS_ARN_PREFIX =
            "arn:aws:states:us-east-1:000000000000:express:test-sm:";

    private AccountAwareStorageBackend<Execution> execStore;
    private AslExecutor executor;

    private StepFunctionsService buildService() {
        AccountAwareStorageBackend<StateMachine> smStore = AccountAwareStorageBackend.inMemory("000000000000");
        execStore = AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<Activity> actStore = AccountAwareStorageBackend.inMemory("000000000000");

        StorageFactory factory = mock(StorageFactory.class);
        doReturn(smStore).doReturn(execStore).doReturn(actStore)
                .when(factory).create(anyString(), anyString(), any());

        RegionResolver region = mock(RegionResolver.class);
        when(region.buildArn(eq("states"), any(), anyString()))
                .thenAnswer(i -> "arn:aws:states:us-east-1:000000000000:" + i.getArgument(2));

        // A mock executor: executeAsync is a no-op, so the completion callback fires only when a test
        // invokes it explicitly (mirroring what AslExecutor would do on the worker thread).
        executor = mock(AslExecutor.class);
        StepFunctionsService svc =
                new StepFunctionsService(factory, region, executor, new ObjectMapper(), mock(SfnMockLoader.class));

        StateMachine sm = new StateMachine();
        sm.setStateMachineArn(SM_ARN);
        sm.setName("test-sm");
        sm.setRoleArn("arn:aws:iam::000000000000:role/r");
        sm.setType("EXPRESS");
        sm.setDefinition("{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Pass\",\"End\":true}}}");
        smStore.put(SM_ARN, sm);
        return svc;
    }

    /** Asserts {@code arn} is express:&lt;sm&gt;:&lt;name&gt;:&lt;uuid&gt; and returns the trailing uuid segment. */
    private static String expressUuid(String arn, String name) {
        String prefix = EXPRESS_ARN_PREFIX + name + ":";
        assertTrue(arn.startsWith(prefix), "not an express ARN for " + name + ": " + arn);
        return arn.substring(prefix.length());
    }

    @Test
    void sameNameStartsTwiceAsTwoIndependentExecutions() {
        StepFunctionsService service = buildService();
        Execution e1 = service.startExecution(SM_ARN, "again", "{\"n\":1}", REGION);
        Execution e2 = service.startExecution(SM_ARN, "again", "{\"n\":2}", REGION);

        assertNotEquals(e1.getExecutionArn(), e2.getExecutionArn(), "each EXPRESS start gets its own ARN");
        assertEquals("again", e1.getName());
        assertEquals("again", e2.getName());
        // ARN shape: express:<stateMachineName>:<executionName>:<uuid>
        UUID.fromString(expressUuid(e1.getExecutionArn(), "again"));
        UUID.fromString(expressUuid(e2.getExecutionArn(), "again"));
        // Both are stored under their own key, each carrying its own input; neither shadows the other.
        assertEquals("{\"n\":1}", execStore.get(e1.getExecutionArn()).get().getInput());
        assertEquals("{\"n\":2}", execStore.get(e2.getExecutionArn()).get().getInput());
        verify(executor, times(2)).executeAsync(any(), any(), any(), any(), any());
    }

    @Test
    void completingTheFirstStartDoesNotClobberTheSecond() {
        StepFunctionsService service = buildService();
        Execution e1 = service.startExecution(SM_ARN, "again", "{\"n\":1}", REGION);
        Execution e2 = service.startExecution(SM_ARN, "again", "{\"n\":2}", REGION);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<BiConsumer<Execution, List<HistoryEvent>>> onUpdate =
                ArgumentCaptor.forClass(BiConsumer.class);
        verify(executor, times(2)).executeAsync(any(), any(), any(), any(), onUpdate.capture());

        // Complete the FIRST start exactly as AslExecutor would: mutate its Execution, fire its callback.
        e1.setStatus("SUCCEEDED");
        e1.setOutput("{\"done\":1}");
        onUpdate.getAllValues().get(0).accept(e1, new ArrayList<>());

        assertEquals("SUCCEEDED", execStore.get(e1.getExecutionArn()).get().getStatus());
        // The second execution, on its own key, is untouched by the first's completion.
        Execution stored2 = execStore.get(e2.getExecutionArn()).get();
        assertEquals("RUNNING", stored2.getStatus());
        assertEquals("{\"n\":2}", stored2.getInput());
        assertNull(stored2.getOutput());
    }

    @Test
    void closedNameIsImmediatelyReusable() {
        StepFunctionsService service = buildService();
        Execution e1 = service.startExecution(SM_ARN, "again", "{\"n\":1}", REGION);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<BiConsumer<Execution, List<HistoryEvent>>> onUpdate =
                ArgumentCaptor.forClass(BiConsumer.class);
        verify(executor, times(1)).executeAsync(any(), any(), any(), any(), onUpdate.capture());
        e1.setStatus("SUCCEEDED");
        onUpdate.getValue().accept(e1, new ArrayList<>());

        // Reusing the (now closed) name starts a fresh, independent execution rather than conflicting.
        Execution e2 = service.startExecution(SM_ARN, "again", "{\"n\":1}", REGION);
        assertNotEquals(e1.getExecutionArn(), e2.getExecutionArn());
        assertEquals("RUNNING", execStore.get(e2.getExecutionArn()).get().getStatus());
    }

    @Test
    void concurrentSameNameStartsAllLaunchIndependently() throws Exception {
        StepFunctionsService service = buildService();
        int n = 16;
        List<String> arns = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await();
                        arns.add(service.startExecution(SM_ARN, "again", "{\"n\":1}", REGION).getExecutionArn());
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(errors.isEmpty(), "no EXPRESS same-name start should error: " + errors);
        assertEquals(n, arns.size());
        assertEquals(n, arns.stream().distinct().count(), "every EXPRESS start is its own execution");
        verify(executor, times(n)).executeAsync(any(), any(), any(), any(), any());
    }
}

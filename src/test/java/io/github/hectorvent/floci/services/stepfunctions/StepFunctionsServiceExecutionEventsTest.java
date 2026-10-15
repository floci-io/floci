package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.stepfunctions.model.Activity;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which status changes {@link StepFunctionsService} hands to the {@link StepFunctionsEventPublisher},
 * over a real service with in-memory stores, a mocked {@link AslExecutor} and a mocked publisher.
 * The executor's completion callback is captured and invoked by the test, standing in for the worker
 * thread, so the cases where no event may be published are checked directly instead of by waiting
 * on an empty queue.
 */
class StepFunctionsServiceExecutionEventsTest {

    private static final String REGION = "us-east-1";
    private static final String STANDARD_SM_ARN = "arn:aws:states:us-east-1:000000000000:stateMachine:standard-sm";
    private static final String EXPRESS_SM_ARN = "arn:aws:states:us-east-1:000000000000:stateMachine:express-sm";

    private AslExecutor executor;
    private StepFunctionsEventPublisher publisher;
    // The status at the moment of each publish: the service hands over the live Execution, which
    // later transitions keep mutating.
    private final List<String> publishedStatuses = new ArrayList<>();

    private StepFunctionsService buildService() {
        AccountAwareStorageBackend<StateMachine> smStore = AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<Execution> execStore = AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<Activity> actStore = AccountAwareStorageBackend.inMemory("000000000000");

        StorageFactory factory = mock(StorageFactory.class);
        doReturn(smStore).doReturn(execStore).doReturn(actStore)
                .when(factory).create(anyString(), anyString(), any());

        RegionResolver region = mock(RegionResolver.class);
        when(region.buildArn(eq("states"), any(), anyString()))
                .thenAnswer(i -> "arn:aws:states:us-east-1:000000000000:" + i.getArgument(2));

        executor = mock(AslExecutor.class);
        publisher = mock(StepFunctionsEventPublisher.class);
        doAnswer(i -> publishedStatuses.add(i.<Execution>getArgument(0).getStatus()))
                .when(publisher).emitStatusChange(any());
        StepFunctionsService service = new StepFunctionsService(factory, region, executor, new ObjectMapper(),
                mock(SfnMockLoader.class), publisher);

        smStore.put(STANDARD_SM_ARN, stateMachine(STANDARD_SM_ARN, "standard-sm", "STANDARD"));
        smStore.put(EXPRESS_SM_ARN, stateMachine(EXPRESS_SM_ARN, "express-sm", "EXPRESS"));
        return service;
    }

    private static StateMachine stateMachine(String arn, String name, String type) {
        StateMachine sm = new StateMachine();
        sm.setStateMachineArn(arn);
        sm.setName(name);
        sm.setRoleArn("arn:aws:iam::000000000000:role/r");
        sm.setType(type);
        sm.setDefinition("{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Pass\",\"End\":true}}}");
        return sm;
    }

    @SuppressWarnings("unchecked")
    private BiConsumer<Execution, List<HistoryEvent>> completionCallback() {
        ArgumentCaptor<BiConsumer<Execution, List<HistoryEvent>>> callback = ArgumentCaptor.forClass(BiConsumer.class);
        verify(executor).executeAsync(any(), any(), any(), any(), callback.capture());
        return callback.getValue();
    }

    @Test
    void standardExecutionPublishesRunningThenItsTerminalStatus() {
        StepFunctionsService service = buildService();
        Execution exec = service.startExecution(STANDARD_SM_ARN, "run-1", "{}", REGION);
        BiConsumer<Execution, List<HistoryEvent>> callback = completionCallback();

        exec.setStatus("SUCCEEDED");
        exec.setStopDate(exec.getStartDate() + 1);
        callback.accept(exec, List.of());

        assertEquals(List.of("RUNNING", "SUCCEEDED"), publishedStatuses);
    }

    @Test
    void expressExecutionPublishesNothing() {
        StepFunctionsService service = buildService();
        Execution exec = service.startExecution(EXPRESS_SM_ARN, "run-1", "{}", REGION);
        BiConsumer<Execution, List<HistoryEvent>> callback = completionCallback();

        exec.setStatus("SUCCEEDED");
        exec.setStopDate(exec.getStartDate() + 1);
        callback.accept(exec, List.of());

        verifyNoInteractions(publisher);
    }

    @Test
    void stoppedExecutionPublishesAbortedOnceAndTheWorkerAddsNothing() {
        StepFunctionsService service = buildService();
        Execution exec = service.startExecution(STANDARD_SM_ARN, "run-1", "{}", REGION);
        BiConsumer<Execution, List<HistoryEvent>> callback = completionCallback();

        service.stopExecution(exec.getExecutionArn(), "by test", "Halted");
        // The worker notices the abort and reports through the same callback as any other ending.
        callback.accept(exec, List.of());

        assertEquals(List.of("RUNNING", "ABORTED"), publishedStatuses);
    }

    @Test
    void stoppingAnAlreadyStoppedExecutionPublishesNothingMore() {
        StepFunctionsService service = buildService();
        Execution exec = service.startExecution(STANDARD_SM_ARN, "run-1", "{}", REGION);

        service.stopExecution(exec.getExecutionArn(), null, null);
        service.stopExecution(exec.getExecutionArn(), null, null);

        assertEquals(List.of("RUNNING", "ABORTED"), publishedStatuses);
    }
}

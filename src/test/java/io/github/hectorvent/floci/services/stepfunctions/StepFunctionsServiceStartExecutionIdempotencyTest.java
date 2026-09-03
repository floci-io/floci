package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.stepfunctions.model.Activity;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The StartExecution idempotency DECISION for {@link StepFunctionsService}, over a real service with
 * in-memory stores. Each test pre-seeds an existing execution so StartExecution takes the
 * return-existing / conflict branch, which resolves before {@code aslExecutor.executeAsync}, so the
 * executor is never touched (it cannot be loaded in this offline sandbox: it needs Vert.x). The
 * create-and-launch path and the concurrency race are covered by
 * {@code StepFunctionsServiceStartExecutionRaceTest} (CI-only).
 */
class StepFunctionsServiceStartExecutionIdempotencyTest {

    private static final String SM_ARN = "arn:aws:states:us-east-1:000000000000:stateMachine:test-sm";
    private static final String REGION = "us-east-1";

    private AccountAwareStorageBackend<Execution> execStore;

    private StepFunctionsService buildService(String smType) {
        AccountAwareStorageBackend<StateMachine> smStore = AccountAwareStorageBackend.inMemory("000000000000");
        execStore = AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<Activity> actStore = AccountAwareStorageBackend.inMemory("000000000000");

        StorageFactory factory = mock(StorageFactory.class);
        doReturn(smStore).doReturn(execStore).doReturn(actStore)
                .when(factory).create(anyString(), anyString(), any());

        RegionResolver region = mock(RegionResolver.class);
        when(region.buildArn(eq("states"), any(), anyString()))
                .thenAnswer(i -> "arn:aws:states:us-east-1:000000000000:" + i.getArgument(2));

        // aslExecutor is null: the return-existing / conflict branches never reach executeAsync, and a
        // null field is never dereferenced (AslExecutor cannot be class-loaded here: it needs Vert.x).
        StepFunctionsService svc =
                new StepFunctionsService(factory, region, null, new ObjectMapper(), mock(SfnMockLoader.class));

        StateMachine sm = new StateMachine();
        sm.setStateMachineArn(SM_ARN);
        sm.setName("test-sm");
        sm.setRoleArn("arn:aws:iam::000000000000:role/r");
        sm.setType(smType);
        sm.setDefinition("{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Pass\",\"End\":true}}}");
        smStore.put(SM_ARN, sm);
        return svc;
    }

    private void seedExecution(String name, String input, String status) {
        Execution exec = new Execution();
        exec.setExecutionArn("arn:aws:states:us-east-1:000000000000:execution:test-sm:" + name);
        exec.setStateMachineArn(SM_ARN);
        exec.setName(name);
        exec.setInput(input);
        exec.setStatus(status);
        exec.setStartDate(1234567.0); // distinctive: the idempotent return must preserve the ORIGINAL start date
        execStore.put(exec.getExecutionArn(), exec);
    }

    @Test
    void sameNameSameInputWhileRunningReturnsExisting() {
        StepFunctionsService service = buildService("STANDARD");
        seedExecution("run", "{\"a\":1}", "RUNNING");
        Execution result = service.startExecution(SM_ARN, "run", "{\"a\":1}", REGION);
        assertEquals("arn:aws:states:us-east-1:000000000000:execution:test-sm:run", result.getExecutionArn());
        assertEquals("RUNNING", result.getStatus());
        // Must be the ORIGINAL execution (with its original startDate), not a freshly built one.
        assertEquals(1234567.0, result.getStartDate());
    }

    @Test
    void sameNameDifferentInputConflicts() {
        StepFunctionsService service = buildService("STANDARD");
        seedExecution("run", "{\"a\":1}", "RUNNING");
        AwsException ex = assertThrows(AwsException.class,
                () -> service.startExecution(SM_ARN, "run", "{\"a\":2}", REGION));
        assertEquals("ExecutionAlreadyExists", ex.getErrorCode());
    }

    @Test
    void sameNameAfterCloseConflicts() {
        StepFunctionsService service = buildService("STANDARD");
        seedExecution("run", "{\"a\":1}", "SUCCEEDED");
        AwsException ex = assertThrows(AwsException.class,
                () -> service.startExecution(SM_ARN, "run", "{\"a\":1}", REGION));
        assertEquals("ExecutionAlreadyExists", ex.getErrorCode());
    }

    @Test
    void expressGetsNoIdempotentReturn() {
        StepFunctionsService service = buildService("EXPRESS");
        seedExecution("run", "{\"a\":1}", "RUNNING");
        // STANDARD-only idempotency: EXPRESS with the same name+input still conflicts (it does not take the
        // return-existing branch). AWS actually allows EXPRESS name reuse: a known divergence, out of scope.
        assertThrows(AwsException.class,
                () -> service.startExecution(SM_ARN, "run", "{\"a\":1}", REGION));
    }
}

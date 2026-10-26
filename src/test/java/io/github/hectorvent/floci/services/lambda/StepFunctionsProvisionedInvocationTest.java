package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.PendingInvocation;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import io.github.hectorvent.floci.services.stepfunctions.AslExecutor;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class StepFunctionsProvisionedInvocationTest {

    private static final String REGION = "us-east-2";
    private static final String NAME = "provisioned-sfn";
    private static final String ARN = "arn:aws:lambda:us-east-2:000000000000:function:" + NAME;

    @Inject
    AslExecutor executor;

    @InjectMock
    LambdaFunctionStore functions;

    @InjectMock
    LambdaAliasStore aliases;

    @InjectMock
    WarmPool warmPool;

    @ParameterizedTest
    @CsvSource({"live,true", "live,false", "1,true", "1,false"})
    void qualifiedTasksUseProvisionedRuntimeWithoutColdAcquisition(String qualifier, boolean optimized) {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName(NAME);
        function.setFunctionArn(ARN + ":1");
        function.setVersion("1");
        function.setTimeout(3);
        when(functions.get(REGION, NAME, "1")).thenReturn(Optional.of(function));
        LambdaAlias alias = new LambdaAlias();
        alias.setName("live");
        alias.setFunctionName(NAME);
        alias.setFunctionVersion("1");
        when(aliases.get(REGION, NAME, "live")).thenReturn(Optional.of(alias));

        RuntimeApiServer runtime = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("provisioned-runtime", NAME, runtime, ContainerState.WARM);
        when(warmPool.acquireProvisioned(function, qualifier)).thenReturn(handle);
        doAnswer(invocation -> {
            PendingInvocation pending = invocation.getArgument(0);
            pending.getResultFuture().complete(new InvokeResult(200, null,
                    "{\"provisioned\":true}".getBytes(StandardCharsets.UTF_8), null, pending.getRequestId()));
            return pending.getResultFuture();
        }).when(runtime).enqueue(any());

        String functionRef = ARN + ":" + qualifier;
        String task = optimized
                ? """
                  "Resource":"arn:aws:states:::lambda:invoke",
                  "Parameters":{"FunctionName":"%s","Payload":{}}
                  """.formatted(functionRef)
                : "\"Resource\":\"" + functionRef + "\"";
        StateMachine stateMachine = new StateMachine();
        stateMachine.setStateMachineArn("arn:aws:states:us-east-2:000000000000:stateMachine:provisioned");
        stateMachine.setRoleArn("arn:aws:iam::000000000000:role/test");
        stateMachine.setDefinition("""
                {"StartAt":"Call","States":{"Call":{"Type":"Task",%s,"End":true}}}
                """.formatted(task));
        Execution execution = new Execution();
        execution.setExecutionArn("arn:aws:states:us-east-2:000000000000:execution:provisioned:test");
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{}");

        executor.executeSync(stateMachine, execution, new ArrayList<>(), (updated, events) -> { });

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        verify(warmPool).acquireProvisioned(function, qualifier);
        verify(warmPool, never()).acquire(any());
        verify(warmPool).release(handle);
    }
}

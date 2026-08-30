package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.CustomResourceLiveness;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A CDK provider-framework waiter's {@code framework.isComplete} poll is itself a Lambda Task in
 * the state machine Step Functions runs on our behalf, so it lands in {@link
 * AslExecutor#invokeResource} rather than {@link
 * io.github.hectorvent.floci.services.lambda.LambdaService#invoke}. Greptile/Copilot both flagged
 * that this path never told {@link CustomResourceLiveness} about the poll, so a long-but-progressing
 * custom resource's idle budget kept ticking down as if no polls were arriving at all.
 */
@QuarkusTest
class AslExecutorCustomResourceLivenessTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";
    private static final String FUNCTION_NAME = "framework-onEvent";
    private static final String FUNCTION_ARN =
            "arn:aws:lambda:%s:%s:function:%s".formatted(REGION, ACCOUNT, FUNCTION_NAME);
    private static final String TOKEN = "6ef77fe2-38c8-41b0-bec6-68879687fbc8";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CustomResourceLiveness customResourceLiveness;
    private AslExecutor executor;

    @Inject
    Vertx vertx;

    @BeforeEach
    void setUp() throws Exception {
        LambdaExecutorService lambdaExecutor = mock(LambdaExecutorService.class);
        LambdaFunctionStore functionStore = mock(LambdaFunctionStore.class);
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName(FUNCTION_NAME);
        function.setFunctionArn(FUNCTION_ARN);
        customResourceLiveness = mock(CustomResourceLiveness.class);

        when(functionStore.get(REGION, FUNCTION_NAME)).thenReturn(Optional.of(function));
        when(lambdaExecutor.invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null, "{}".getBytes(), null, "poll-request"));

        executor = new AslExecutor(
                lambdaExecutor,
                functionStore,
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
                mock(Instance.class), mock(EmulatorConfig.class), vertx, customResourceLiveness);
    }

    @Test
    void waiterPollThroughDirectFunctionArnTouchesTheEmbeddedCallbackToken() throws Exception {
        run("""
                {
                  "StartAt": "Poll",
                  "States": {
                    "Poll": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true
                    }
                  }
                }
                """.formatted(FUNCTION_ARN),
                "{\"RequestType\":\"Create\",\"ResponseURL\":\"http://host:4566/cfn-response/" + TOKEN + "\"}");

        verify(customResourceLiveness).touch(TOKEN);
    }

    @Test
    void waiterPollThroughOptimizedIntegrationTouchesTheEmbeddedCallbackToken() throws Exception {
        run("""
                {
                  "StartAt": "Poll",
                  "States": {
                    "Poll": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::lambda:invoke",
                      "Parameters": {"FunctionName": "%s", "Payload.$": "$"},
                      "End": true
                    }
                  }
                }
                """.formatted(FUNCTION_ARN),
                "{\"RequestType\":\"Create\",\"ResponseURL\":\"http://host:4566/cfn-response/" + TOKEN + "\"}");

        verify(customResourceLiveness).touch(TOKEN);
    }

    @Test
    void ordinaryLambdaInvokeWithNoCallbackUrlNeverTouchesLiveness() throws Exception {
        run("""
                {
                  "StartAt": "Poll",
                  "States": {
                    "Poll": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true
                    }
                  }
                }
                """.formatted(FUNCTION_ARN),
                "{\"Records\":[{\"body\":\"hello\"}]}");

        verify(customResourceLiveness, never()).touch(org.mockito.ArgumentMatchers.anyString());
    }

    private void run(String definition, String input) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("waiter-liveness");
        stateMachine.setStateMachineArn(
                "arn:aws:states:%s:%s:stateMachine:waiter-liveness".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("waiter-liveness-execution");
        execution.setExecutionArn("arn:aws:states:%s:%s:execution:waiter-liveness:run-1"
                .formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

        List<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        assertEquals("SUCCEEDED", execution.getStatus());
    }
}

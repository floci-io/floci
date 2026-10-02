package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler;
import io.github.hectorvent.floci.services.lambda.LambdaConcurrencyLimiter;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.scheduler.SchedulerController;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.sns.SnsJsonHandler;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AslExecutorLambdaThrottleTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";
    private static final String FUNCTION_NAME = "concurrency-limited-lambda";
    private static final String FUNCTION_ARN =
            "arn:aws:lambda:%s:%s:function:%s".formatted(REGION, ACCOUNT, FUNCTION_NAME);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LambdaExecutorService lambdaExecutor;
    private LambdaFunction function;
    private LambdaFunctionStore functionStore;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        lambdaExecutor = mock(LambdaExecutorService.class);
        functionStore = mock(LambdaFunctionStore.class);
        function = new LambdaFunction();
        function.setFunctionName(FUNCTION_NAME);
        function.setFunctionArn(FUNCTION_ARN);
        when(functionStore.get(REGION, FUNCTION_NAME)).thenReturn(Optional.of(function));

        config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambdaConfig = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.lambda()).thenReturn(lambdaConfig);
        when(lambdaConfig.regionConcurrencyLimit()).thenReturn(1);
        when(lambdaConfig.unreservedConcurrencyMin()).thenReturn(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lambdaThrottleRetrySucceedsAfterCapacityBecomesAvailable(boolean optimized) throws Exception {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(config);
        LambdaConcurrencyLimiter.Permit held = limiter.acquire(function);
        AslExecutor executor = executor(nanos -> held.close());
        when(lambdaExecutor.invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(ignored -> {
                    try (LambdaConcurrencyLimiter.Permit invocationPermit = limiter.acquire(function)) {
                        return new InvokeResult(200, null,
                                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), null, "request-id");
                    }
                });

        Execution execution = run(executor, definition(optimized, 2));

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertTrue(optimized
                ? output.path("Payload").path("ok").asBoolean()
                : output.path("ok").asBoolean());
        verify(lambdaExecutor, times(2))
                .invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exhaustedLambdaThrottlePreservesServiceError(boolean optimized) {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(config);
        try (LambdaConcurrencyLimiter.Permit held = limiter.acquire(function)) {
            AslExecutor executor = executor(nanos -> {
            });
            when(lambdaExecutor.invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse)))
                    .thenAnswer(ignored -> {
                        LambdaConcurrencyLimiter.Permit unexpectedPermit = limiter.acquire(function);
                        unexpectedPermit.close();
                        throw new AssertionError("concurrency limit should remain exhausted");
                    });

            Execution execution = run(executor, definition(optimized, 2));

            assertEquals("FAILED", execution.getStatus());
            assertEquals("Lambda.TooManyRequestsException", execution.getError());
            assertTrue(execution.getCause().contains("Rate Exceeded."));
            verify(lambdaExecutor, times(3))
                    .invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "false, ServiceException",
            "true, ServiceException",
            "false, AWSLambdaException",
            "true, AWSLambdaException",
            "false, SdkClientException",
            "true, SdkClientException"
    })
    void lambdaServiceErrorsUseLambdaNamespace(boolean optimized, String errorCode) {
        AslExecutor executor = executor(nanos -> {
        });
        when(lambdaExecutor.invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenThrow(new AwsException(errorCode, "synthetic Lambda service failure", 500));

        Execution execution = run(executor, definition(optimized, 2));

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Lambda." + errorCode, execution.getError());
        assertTrue(execution.getCause().contains("synthetic Lambda service failure"));
        verify(lambdaExecutor, times(1))
                .invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonAwsLambdaRuntimeFailureRemainsTerminal(boolean optimized) {
        AslExecutor executor = executor(nanos -> {
        });
        when(lambdaExecutor.invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenThrow(new IllegalStateException("broken integration"));

        Execution execution = run(executor, definition(optimized, 2));

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertTrue(execution.getCause().contains("broken integration"));
        verify(lambdaExecutor, times(1))
                .invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    private String definition(boolean optimized, int maxAttempts) {
        String resource = optimized ? "arn:aws:states:::lambda:invoke" : FUNCTION_ARN;
        String parameters = optimized
                ? "\"Parameters\": {\"FunctionName\": \"%s\", \"Payload\": {}},".formatted(FUNCTION_ARN)
                : "";
        return """
                {
                  "StartAt": "Invoke",
                  "States": {
                    "Invoke": {
                      "Type": "Task",
                      "Resource": "%s",
                      %s
                      "Retry": [{
                        "ErrorEquals": ["Lambda.TooManyRequestsException", "States.Runtime"],
                        "IntervalSeconds": 1,
                        "MaxAttempts": %d,
                        "BackoffRate": 2,
                        "JitterStrategy": "FULL"
                      }],
                      "End": true
                    }
                  }
                }
                """.formatted(resource, parameters, maxAttempts);
    }

    private AslExecutor executor(AslExecutor.Sleeper sleeper) {
        return new AslExecutor(
                lambdaExecutor,
                functionStore,
                mock(DynamoDbFacade.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class), mock(SnsJsonHandler.class),
                mock(CloudFormationQueryHandler.class),
                mock(Ec2Service.class),
                mock(S3Service.class),
                mock(EcsService.class),
                mock(EcsJsonHandler.class),
                mock(EventBridgeHandler.class),
                mock(SchedulerService.class),
                mock(SchedulerController.class),
                objectMapper,
                new JsonataEvaluator(objectMapper),
                mock(Instance.class), config, null, null,
                Clock.systemUTC(), sleeper, 30);
    }

    private Execution run(AslExecutor executor, String definition) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("lambda-throttle-test");
        stateMachine.setStateMachineArn(
                "arn:aws:states:%s:%s:stateMachine:lambda-throttle-test".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("lambda-throttle-execution");
        execution.setExecutionArn(
                "arn:aws:states:%s:%s:execution:lambda-throttle-test:lambda-throttle-execution"
                        .formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{}");

        ArrayList<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }
}

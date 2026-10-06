package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.lambda.model.Runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lambda durable functions through the AWS SDK for Java v2: the Invoke extensions and the
 * durable execution APIs, whose execution ARN travels percent-encoded in the request path.
 */
@DisplayName("Lambda durable functions")
class LambdaDurableFunctionsTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";
    private static final String FN = TestFixtures.uniqueName("fn-durable-exec");
    private static final String CALLBACK_FN = TestFixtures.uniqueName("fn-durable-callback");
    private static final String CHAIN_FN = TestFixtures.uniqueName("fn-durable-chain");
    private static final String CHAIN_TARGET_FN = TestFixtures.uniqueName("fn-durable-chain-target");

    private static LambdaClient lambda;

    @BeforeAll
    static void setup() {
        lambda = TestFixtures.lambdaClient();
    }

    @AfterAll
    static void cleanup() {
        if (lambda != null) {
            for (String name : new String[] {FN, CALLBACK_FN, CHAIN_FN, CHAIN_TARGET_FN}) {
                try {
                    lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(name).build());
                } catch (Exception ignored) {
                    // Cleanup only. A failed delete must not hide the test result.
                }
            }
            lambda.close();
        }
    }

    @Test
    @DisplayName("a durable execution runs to completion and is readable through the execution APIs")
    void durableExecutionRunsAndIsReadable() {
        Assumptions.assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "skipping: Lambda dispatch (Docker) not available in this environment");

        CreateFunctionResponse created = lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FN)
                .runtime(Runtime.PYTHON3_14)
                .role(ROLE)
                .handler("lambda_function.handler")
                .timeout(30)
                .publish(true)
                .durableConfig(DurableConfig.builder().executionTimeout(120).retentionPeriodInDays(1).build())
                .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(LambdaUtils.durablePythonZip())).build())
                .build());
        assertThat(created.version()).isEqualTo("1");

        InvokeResponse invoked = lambda.invoke(InvokeRequest.builder()
                .functionName(FN + ":1")
                .durableExecutionName("compat-sync")
                .payload(SdkBytes.fromUtf8String("{\"wait\": 2}"))
                .build());
        assertThat(invoked.statusCode()).isEqualTo(200);
        assertThat(invoked.functionError()).isNull();
        assertThat(invoked.payload().asUtf8String()).contains("\"done\": true");
        String arn = invoked.durableExecutionArn();
        assertThat(arn).contains(":function:" + FN + ":1/durable-execution/compat-sync/");

        GetDurableExecutionResponse execution = lambda.getDurableExecution(GetDurableExecutionRequest.builder()
                .durableExecutionArn(arn)
                .includeExecutionData(true)
                .build());
        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(execution.durableExecutionName()).isEqualTo("compat-sync");
        assertThat(execution.version()).isEqualTo("1");
        assertThat(execution.inputPayload()).isEqualTo("{\"wait\": 2}");
        assertThat(execution.result()).contains("\"done\": true");
        assertThat(execution.durableConfig().executionTimeout()).isEqualTo(120);

        ListDurableExecutionsByFunctionResponse listed = lambda.listDurableExecutionsByFunction(
                ListDurableExecutionsByFunctionRequest.builder().functionName(FN).qualifier("1").build());
        assertThat(listed.durableExecutions()).extracting(Execution::durableExecutionArn).containsExactly(arn);
        assertThat(listed.durableExecutions().get(0).status()).isEqualTo(ExecutionStatus.SUCCEEDED);

        GetDurableExecutionHistoryResponse history = lambda.getDurableExecutionHistory(
                GetDurableExecutionHistoryRequest.builder().durableExecutionArn(arn).includeExecutionData(true).build());
        assertThat(history.events()).extracting(Event::eventType)
                .startsWith(EventType.EXECUTION_STARTED)
                .contains(EventType.STEP_SUCCEEDED, EventType.WAIT_STARTED, EventType.WAIT_SUCCEEDED,
                        EventType.INVOCATION_COMPLETED)
                .endsWith(EventType.EXECUTION_SUCCEEDED);
        assertThat(history.events().get(0).executionStartedDetails().input().payload()).isEqualTo("{\"wait\": 2}");

        StopDurableExecutionResponse stopped = lambda.stopDurableExecution(StopDurableExecutionRequest.builder()
                .durableExecutionArn(arn)
                .build());
        assertThat(stopped.stopTimestamp())
                .as("stopping a finished execution reports the time it finished")
                .isEqualTo(execution.endTimestamp());

        assertThatThrownBy(() -> lambda.invoke(InvokeRequest.builder()
                .functionName(FN + ":1")
                .durableExecutionName("compat-sync")
                .payload(SdkBytes.fromUtf8String("{\"wait\": 3}"))
                .build()))
                .isInstanceOf(DurableExecutionAlreadyStartedException.class);

        assertThatThrownBy(() -> lambda.invoke(InvokeRequest.builder()
                .functionName(FN)
                .payload(SdkBytes.fromUtf8String("{}"))
                .build()))
                .isInstanceOf(InvalidParameterValueException.class)
                .hasMessageContaining("You cannot invoke a durable function using an unqualified ARN.");
    }

    @Test
    @DisplayName("a durable callback is completed through the callback APIs and resumes the execution")
    void durableCallbackResumesTheExecution() throws InterruptedException {
        Assumptions.assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "skipping: Lambda dispatch (Docker) not available in this environment");

        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(CALLBACK_FN)
                .runtime(Runtime.PYTHON3_14)
                .role(ROLE)
                .handler("lambda_function.handler")
                .timeout(30)
                .durableConfig(DurableConfig.builder().executionTimeout(120).retentionPeriodInDays(1).build())
                .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(LambdaUtils.durablePythonZip())).build())
                .build());

        String arn = lambda.invoke(InvokeRequest.builder()
                .functionName(CALLBACK_FN + ":$LATEST")
                .invocationType(InvocationType.EVENT)
                .payload(SdkBytes.fromUtf8String("{\"callback\": true}"))
                .build()).durableExecutionArn();

        String callbackId = null;
        for (int i = 0; i < 60 && callbackId == null; i++) {
            callbackId = lambda.getDurableExecutionHistory(GetDurableExecutionHistoryRequest.builder()
                            .durableExecutionArn(arn).build())
                    .events().stream()
                    .filter(event -> event.eventType() == EventType.CALLBACK_STARTED)
                    .map(event -> event.callbackStartedDetails().callbackId())
                    .findFirst().orElse(null);
            if (callbackId == null) {
                Thread.sleep(500);
            }
        }
        assertThat(callbackId).as("the function started a callback").isNotNull();
        String id = callbackId;

        lambda.sendDurableExecutionCallbackHeartbeat(SendDurableExecutionCallbackHeartbeatRequest.builder()
                .callbackId(id).build());
        lambda.sendDurableExecutionCallbackSuccess(SendDurableExecutionCallbackSuccessRequest.builder()
                .callbackId(id).result(SdkBytes.fromUtf8String("{\"approved\": true}")).build());

        GetDurableExecutionResponse execution = null;
        for (int i = 0; i < 60; i++) {
            execution = lambda.getDurableExecution(GetDurableExecutionRequest.builder()
                    .durableExecutionArn(arn).includeExecutionData(true).build());
            if (execution.status() != ExecutionStatus.RUNNING) {
                break;
            }
            Thread.sleep(500);
        }
        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(execution.result()).isEqualTo("{\"approved\": true}");

        assertThatThrownBy(() -> lambda.sendDurableExecutionCallbackSuccess(
                SendDurableExecutionCallbackSuccessRequest.builder().callbackId(id).build()))
                .isInstanceOf(CallbackTimeoutException.class);
    }

    @Test
    @DisplayName("a durable function invokes another function and receives its result")
    void durableChainedInvokeReturnsTheTargetResult() {
        Assumptions.assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "skipping: Lambda dispatch (Docker) not available in this environment");

        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(CHAIN_TARGET_FN)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(LambdaUtils.handlerZip())).build())
                .build());
        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(CHAIN_FN)
                .runtime(Runtime.PYTHON3_14)
                .role(ROLE)
                .handler("lambda_function.handler")
                .timeout(30)
                .durableConfig(DurableConfig.builder().executionTimeout(120).retentionPeriodInDays(1).build())
                .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(LambdaUtils.durablePythonZip())).build())
                .build());

        InvokeResponse invoked = lambda.invoke(InvokeRequest.builder()
                .functionName(CHAIN_FN + ":$LATEST")
                .payload(SdkBytes.fromUtf8String("{\"chain\": \"" + CHAIN_TARGET_FN + "\"}"))
                .build());

        assertThat(invoked.functionError()).isNull();
        assertThat(invoked.payload().asUtf8String()).contains("Hello, Durable!");
        GetDurableExecutionHistoryResponse history = lambda.getDurableExecutionHistory(
                GetDurableExecutionHistoryRequest.builder().durableExecutionArn(invoked.durableExecutionArn()).build());
        assertThat(history.events()).extracting(Event::eventType)
                .contains(EventType.CHAINED_INVOKE_STARTED, EventType.CHAINED_INVOKE_SUCCEEDED);
        Event started = history.events().stream()
                .filter(event -> event.eventType() == EventType.CHAINED_INVOKE_STARTED)
                .findFirst().orElseThrow();
        assertThat(started.chainedInvokeStartedDetails().functionName()).isEqualTo(CHAIN_TARGET_FN);
        assertThat(started.chainedInvokeStartedDetails().executedVersion()).isEqualTo("$LATEST");
    }
}

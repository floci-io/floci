package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.lambda.model.Runtime;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Lambda - Function concurrency")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaConcurrencyTest {

    private static final String FUNCTION_NAME = "sdk-test-concurrency-fn";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";

    private static LambdaClient lambda;

    @BeforeAll
    static void setup() {
        lambda = TestFixtures.lambdaClient();
        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FUNCTION_NAME)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip()))
                        .build())
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (lambda != null) {
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder()
                        .functionName(FUNCTION_NAME).build());
            } catch (Exception ignored) {}
            lambda.close();
        }
    }

    @Test
    @Order(1)
    void getFunctionConcurrency_unset_returnsEmpty() {
        GetFunctionConcurrencyResponse response = lambda.getFunctionConcurrency(
                GetFunctionConcurrencyRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .build());

        assertThat(response.reservedConcurrentExecutions()).isNull();
    }

    @Test
    @Order(2)
    void putFunctionConcurrency_setsAndReturnsValue() {
        PutFunctionConcurrencyResponse response = lambda.putFunctionConcurrency(
                PutFunctionConcurrencyRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .reservedConcurrentExecutions(5)
                        .build());

        assertThat(response.reservedConcurrentExecutions()).isEqualTo(5);
    }

    @Test
    @Order(3)
    void getFunctionConcurrency_afterPut_returnsValue() {
        GetFunctionConcurrencyResponse response = lambda.getFunctionConcurrency(
                GetFunctionConcurrencyRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .build());

        assertThat(response.reservedConcurrentExecutions()).isEqualTo(5);
    }

    @Test
    @Order(4)
    void putFunctionConcurrency_updatesExistingValue() {
        PutFunctionConcurrencyResponse response = lambda.putFunctionConcurrency(
                PutFunctionConcurrencyRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .reservedConcurrentExecutions(10)
                        .build());

        assertThat(response.reservedConcurrentExecutions()).isEqualTo(10);
    }

    @Test
    @Order(5)
    void putFunctionConcurrency_zeroIsAllowed() {
        PutFunctionConcurrencyResponse response = lambda.putFunctionConcurrency(
                PutFunctionConcurrencyRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .reservedConcurrentExecutions(0)
                        .build());

        assertThat(response.reservedConcurrentExecutions()).isEqualTo(0);
    }

    @Test
    @Order(6)
    void deleteFunctionConcurrency_clearsValue() {
        lambda.deleteFunctionConcurrency(DeleteFunctionConcurrencyRequest.builder()
                .functionName(FUNCTION_NAME)
                .build());

        GetFunctionConcurrencyResponse response = lambda.getFunctionConcurrency(
                GetFunctionConcurrencyRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .build());

        assertThat(response.reservedConcurrentExecutions()).isNull();
    }

    @Test
    @Order(7)
    void putFunctionConcurrency_unknownFunction_throws404() {
        assertThatThrownBy(() -> lambda.putFunctionConcurrency(
                PutFunctionConcurrencyRequest.builder()
                        .functionName("does-not-exist")
                        .reservedConcurrentExecutions(5)
                        .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(8)
    void getFunctionConcurrency_unknownFunction_throws404() {
        assertThatThrownBy(() -> lambda.getFunctionConcurrency(
                GetFunctionConcurrencyRequest.builder()
                        .functionName("does-not-exist")
                        .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(9)
    void putFunctionConcurrency_exceedsAccountUnreservedMin_throwsLimitExceeded() {
        // Floci default: regionLimit=1000, unreservedMin=100 → max single Put = 900
        // The Lambda SDK v2 model does not declare LimitExceededException as a
        // dedicated subclass, so the SDK surfaces it as the generic
        // LambdaException. We therefore assert the wire-level identity
        // (status code + __type error code) rather than a Java type, which is
        // what AWS clients actually discriminate on.
        assertThatThrownBy(() -> lambda.putFunctionConcurrency(
                PutFunctionConcurrencyRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .reservedConcurrentExecutions(901)
                        .build()))
                .isInstanceOfSatisfying(LambdaException.class, ex -> {
                    assertThat(ex.statusCode()).isEqualTo(400);
                    assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("LimitExceededException");
                    assertThat(ex.getMessage()).contains("UnreservedConcurrentExecution");
                });
    }

    @Test
    @Order(10)
    void invoke_whenReservedZero_throwsTooManyRequests() {
        lambda.putFunctionConcurrency(PutFunctionConcurrencyRequest.builder()
                .functionName(FUNCTION_NAME)
                .reservedConcurrentExecutions(0)
                .build());

        // Event-type invoke still goes through the concurrency gate; reserved=0
        // should throttle every request regardless of invocation type.
        assertThatThrownBy(() -> lambda.invoke(InvokeRequest.builder()
                .functionName(FUNCTION_NAME)
                .invocationType(InvocationType.EVENT)
                .payload(SdkBytes.fromUtf8String("{}"))
                .build()))
                .isInstanceOf(TooManyRequestsException.class);

        // Clear so teardown and other tests are not affected
        lambda.deleteFunctionConcurrency(DeleteFunctionConcurrencyRequest.builder()
                .functionName(FUNCTION_NAME).build());
    }

    @Test
    @Order(11)
    void invoke_dryRunBypassesConcurrencyGate() {
        lambda.putFunctionConcurrency(PutFunctionConcurrencyRequest.builder()
                .functionName(FUNCTION_NAME)
                .reservedConcurrentExecutions(0)
                .build());

        // DryRun validates inputs without dispatching; it must not be throttled.
        InvokeResponse response = lambda.invoke(InvokeRequest.builder()
                .functionName(FUNCTION_NAME)
                .invocationType(InvocationType.DRY_RUN)
                .payload(SdkBytes.fromUtf8String("{}"))
                .build());

        assertThat(response.statusCode()).isEqualTo(204);

        lambda.deleteFunctionConcurrency(DeleteFunctionConcurrencyRequest.builder()
                .functionName(FUNCTION_NAME).build());
    }

    @Test
    @Order(12)
    void invoke_withVersionQualifier_stillHonorsReservedOnLatest() {
        // Regression guard: if a future change adds Qualifier routing that
        // resolves to a published version snapshot, the snapshot currently
        // has reservedConcurrentExecutions=null and would silently bypass a
        // reserved=0 on $LATEST. Today Floci ignores the qualifier and
        // routes the invoke to $LATEST, so reserved=0 must still throttle.
        // Keeping this test green after a qualifier-routing change will
        // require copying the reservation onto the snapshot (or keying the
        // limiter off the base ARN).
        lambda.publishVersion(PublishVersionRequest.builder()
                .functionName(FUNCTION_NAME).build());
        lambda.putFunctionConcurrency(PutFunctionConcurrencyRequest.builder()
                .functionName(FUNCTION_NAME)
                .reservedConcurrentExecutions(0)
                .build());

        assertThatThrownBy(() -> lambda.invoke(InvokeRequest.builder()
                .functionName(FUNCTION_NAME)
                .qualifier("1")
                .invocationType(InvocationType.EVENT)
                .payload(SdkBytes.fromUtf8String("{}"))
                .build()))
                .isInstanceOf(TooManyRequestsException.class);

        lambda.deleteFunctionConcurrency(DeleteFunctionConcurrencyRequest.builder()
                .functionName(FUNCTION_NAME).build());
    }
}

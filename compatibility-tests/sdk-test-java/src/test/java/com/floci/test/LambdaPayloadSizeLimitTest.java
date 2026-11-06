package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Lambda - Invoke payload size limits")
class LambdaPayloadSizeLimitTest {

    private static final int _1_MB = 1 * 1024 * 1024;
    private static final int _6_MB = 6 * 1024 * 1024;

    private static final int _5_MB = 5 * 1024 * 1024;

    private static final String FN          = "sdk-size-limit-fn";
    private static final String FN_LARGE    = "sdk-size-limit-large-response-fn";
    private static final String FN_5MB      = "sdk-size-limit-5mb-response-fn";
    private static final String ROLE        = "arn:aws:iam::000000000000:role/lambda-role";

    private static LambdaClient lambda;

    @BeforeAll
    static void setup() {
        lambda = TestFixtures.lambdaClient();
        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FN)
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
        if (lambda == null) return;
        for (String name : new String[]{FN, FN_LARGE, FN_5MB}) {
            try { lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(name).build()); }
            catch (Exception ignored) {}
        }
        lambda.close();
    }

    // ── Request payload limits ──────────────────────────────────────────────

    @Test
    @DisplayName("sync invoke with payload > 6 MB returns 413 RequestTooLargeException")
    void syncInvoke_payloadExceeds6MB_throwsRequestTooLargeException() {
        assertThatThrownBy(() -> lambda.invoke(InvokeRequest.builder()
                .functionName(FN)
                .invocationType(InvocationType.REQUEST_RESPONSE)
                .payload(SdkBytes.fromByteArray(new byte[_6_MB + 1]))
                .build()))
                .isInstanceOf(RequestTooLargeException.class)
                .satisfies(ex -> assertThat(((RequestTooLargeException) ex).statusCode()).isEqualTo(413));
    }

    @Test
    @DisplayName("sync invoke with payload exactly 6 MB is not rejected by size check")
    void syncInvoke_payloadExactly6MB_isNotRejected() {
        // DryRun avoids waiting for Lambda dispatch; verifies no 413 from size check
        InvokeResponse resp = lambda.invoke(InvokeRequest.builder()
                .functionName(FN)
                .invocationType(InvocationType.DRY_RUN)
                .payload(SdkBytes.fromByteArray(new byte[_6_MB]))
                .build());

        assertThat(resp.statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("async invoke with payload > 1 MB returns 413 RequestTooLargeException")
    void asyncInvoke_payloadExceeds1MB_throwsRequestTooLargeException() {
        assertThatThrownBy(() -> lambda.invoke(InvokeRequest.builder()
                .functionName(FN)
                .invocationType(InvocationType.EVENT)
                .payload(SdkBytes.fromByteArray(new byte[_1_MB + 1]))
                .build()))
                .isInstanceOf(RequestTooLargeException.class)
                .satisfies(ex -> assertThat(((RequestTooLargeException) ex).statusCode()).isEqualTo(413));
    }

    @Test
    @DisplayName("async invoke with payload exactly 1 MB is accepted (202)")
    void asyncInvoke_payloadExactly1MB_isAccepted() {
        InvokeResponse resp = lambda.invoke(InvokeRequest.builder()
                .functionName(FN)
                .invocationType(InvocationType.EVENT)
                .payload(SdkBytes.fromByteArray(new byte[_1_MB]))
                .build());

        assertThat(resp.statusCode()).isEqualTo(202);
    }

    // ── Response payload limit ──────────────────────────────────────────────

    @Test
    @DisplayName("sync invoke returning > 6 MB response returns 413 RequestTooLargeException")
    void syncInvoke_responseExceeds6MB_throwsRequestTooLargeException() {
        Assumptions.assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "skipping: Lambda dispatch (Docker) not available in this environment");

        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FN_LARGE)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.largeResponseZip(_6_MB + 1)))
                        .build())
                .build());

        assertThatThrownBy(() -> lambda.invoke(InvokeRequest.builder()
                .functionName(FN_LARGE)
                .invocationType(InvocationType.REQUEST_RESPONSE)
                .payload(SdkBytes.fromUtf8String("{}"))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60)))
                .build()))
                .isInstanceOf(RequestTooLargeException.class)
                .satisfies(ex -> assertThat(((RequestTooLargeException) ex).statusCode()).isEqualTo(413));
    }

    @Test
    @DisplayName("sync invoke returning a 5 MB response succeeds (regression: RuntimeApiServer crashed on bodies > 8 KB via form-limit bug)")
    void syncInvoke_5MBResponse_isReturnedSuccessfully() {
        Assumptions.assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "skipping: Lambda dispatch (Docker) not available in this environment");

        // 5 MB is within the 6 MB AWS limit but well above the 8 KB Netty form-attribute
        // cap that previously caused "Size exceed allowed maximum capacity" when the Lambda
        // runtime POSTed a large response body back to the RuntimeApiServer.
        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FN_5MB)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.largeResponseZip(_5_MB)))
                        .build())
                .build());

        InvokeResponse resp = lambda.invoke(InvokeRequest.builder()
                .functionName(FN_5MB)
                .invocationType(InvocationType.REQUEST_RESPONSE)
                .payload(SdkBytes.fromUtf8String("{}"))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60)))
                .build());

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.functionError()).isNull();
        assertThat(resp.payload().asByteArray().length).isGreaterThan(_5_MB - 100);
    }
}

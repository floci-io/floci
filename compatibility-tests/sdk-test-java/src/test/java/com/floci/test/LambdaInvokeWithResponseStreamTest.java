package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaAsyncClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.InvokeWithResponseStreamCompleteEvent;
import software.amazon.awssdk.services.lambda.model.InvokeWithResponseStreamRequest;
import software.amazon.awssdk.services.lambda.model.InvokeWithResponseStreamResponseHandler;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Lambda - InvokeWithResponseStream")
class LambdaInvokeWithResponseStreamTest {

    private static final String FN = "sdk-invoke-with-response-stream-fn";

    private static LambdaClient lambda;
    private static LambdaAsyncClient lambdaAsync;

    @BeforeAll
    static void setup() {
        lambda = TestFixtures.lambdaClient();
        lambdaAsync = TestFixtures.lambdaAsyncClient();
        lambda.createFunction(r -> r
                .functionName(FN)
                .runtime(Runtime.NODEJS22_X)
                .role("arn:aws:iam::000000000000:role/lambda-role")
                .handler("index.handler")
                .timeout(30)
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.streamingZip()))
                        .build()));
    }

    @AfterAll
    static void cleanup() {
        if (lambda != null) {
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(FN).build());
            } catch (Exception ignored) {
                // The function may already be gone.
            }
            lambda.close();
        }
        if (lambdaAsync != null) {
            lambdaAsync.close();
        }
    }

    @Test
    @DisplayName("streams the payload chunks and one InvokeComplete")
    void streamsPayloadChunksAndOneInvokeComplete() throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        List<InvokeWithResponseStreamCompleteEvent> completes = new ArrayList<>();
        InvokeWithResponseStreamResponseHandler handler = InvokeWithResponseStreamResponseHandler.builder()
                .onResponse(response -> { })
                .subscriber(InvokeWithResponseStreamResponseHandler.Visitor.builder()
                        .onPayloadChunk(chunk -> payload.writeBytes(chunk.payload().asByteArray()))
                        .onInvokeComplete(completes::add)
                        .build())
                .build();

        lambdaAsync.invokeWithResponseStream(InvokeWithResponseStreamRequest.builder()
                        .functionName(FN)
                        .payload(SdkBytes.fromUtf8String("{}"))
                        .build(), handler)
                .get(60, TimeUnit.SECONDS);

        assertThat(payload.toString(StandardCharsets.UTF_8)).isEqualTo("hello world");
        assertThat(completes).hasSize(1);
        assertThat(completes.get(0).errorCode()).isNull();
    }
}

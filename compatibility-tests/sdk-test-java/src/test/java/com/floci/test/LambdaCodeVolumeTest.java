package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("Lambda code large enough to be served from a code volume")
class LambdaCodeVolumeTest {

    private static final String FUNCTION_NAME = TestFixtures.uniqueName("code-volume-fn");

    private static LambdaClient lambda;

    @BeforeAll
    static void setup() {
        assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "Skipping code volume test: Docker dispatch not available in this environment");
        lambda = TestFixtures.lambdaClient();
    }

    @AfterAll
    static void cleanup() {
        if (lambda == null) {
            return;
        }
        try {
            lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(FUNCTION_NAME).build());
        } catch (Exception ignored) {
            // The function does not exist if the test failed before creating it. Cleanup must not hide the test's own result.
        }
        lambda.close();
    }

    @Test
    @DisplayName("Invoke - a function with 33 MB of unzipped code runs")
    void functionWithLargeCodeRuns() throws IOException {
        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FUNCTION_NAME)
                .runtime(Runtime.NODEJS20_X)
                .role("arn:aws:iam::000000000000:role/lambda-role")
                .handler("index.handler")
                .timeout(60)
                .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(largeCodeZip())).build())
                .build());

        InvokeResponse response = lambda.invoke(InvokeRequest.builder().functionName(FUNCTION_NAME).build());

        assertThat(response.functionError()).isNull();
        assertThat(response.payload().asUtf8String()).isEqualTo("{\"ok\":true}");
    }

    /** Zeros compress to almost nothing, so the zip stays small while the unzipped code passes 32 MB. */
    private static byte[] largeCodeZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("exports.handler = async () => ({ ok: true });".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("padding.bin"));
            zip.write(new byte[33 * 1024 * 1024]);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}

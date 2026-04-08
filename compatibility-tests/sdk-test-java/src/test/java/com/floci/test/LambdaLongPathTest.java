package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.lambda.model.Runtime;

import static org.assertj.core.api.Assertions.*;

/**
 * Regression test for https://github.com/floci-io/floci/issues/232
 *
 * Lambda zip extraction was truncating file paths at 99 characters due to the
 * legacy POSIX USTAR tar header name field limit in the hand-rolled tar writer.
 * Files with paths longer than 99 chars were silently renamed to their truncated
 * form, causing "cannot load such file" errors at runtime.
 */
@DisplayName("Lambda - Long file path in ZIP (issue #232)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaLongPathTest {

    private static final String FUNCTION_NAME = "sdk-test-long-path-fn";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LambdaClient lambda;

    @BeforeAll
    static void setup() {
        lambda = TestFixtures.lambdaClient();
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
    void createFunctionWithLongPathZip() {
        CreateFunctionResponse response = lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FUNCTION_NAME)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .timeout(30)
                .memorySize(256)
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.longPathZip()))
                        .build())
                .build());

        assertThat(response.functionName()).isEqualTo(FUNCTION_NAME);
        assertThat(response.stateAsString()).isEqualTo("Active");
    }

    @Test
    @Order(2)
    void longPathFileIsAccessibleAtRuntime() throws Exception {
        InvokeResponse response = lambda.invoke(InvokeRequest.builder()
                .functionName(FUNCTION_NAME)
                .invocationType(InvocationType.REQUEST_RESPONSE)
                .payload(SdkBytes.fromUtf8String("{}"))
                .build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.functionError()).isNull();

        String payload = response.payload().asUtf8String();
        JsonNode result = MAPPER.readTree(payload);

        assertThat(result.get("exists").asBoolean())
                .as("File at path >99 chars must exist inside the container — was it truncated during zip extraction?")
                .isTrue();
        assertThat(result.get("pathLength").asInt())
                .isEqualTo(128); // /var/task/ (10) + relative path (118 chars) — well above the 99-char USTAR limit
    }
}

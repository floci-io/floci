package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.lambda.model.Runtime;

import static org.assertj.core.api.Assertions.*;

/**
 * Compatibility tests for GetFunctionCodeSigningConfig.
 *
 * Regression for https://github.com/floci-io/floci/issues/226:
 * The SDK calls GET /2020-06-30/functions/{name}/code-signing-config — a different
 * API version prefix than most Lambda endpoints (/2015-03-31). Without an explicit
 * route, Floci returned an HTML/XML 404 which the SDK failed to parse as JSON.
 */
@DisplayName("Lambda - GetFunctionCodeSigningConfig (issue #226)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaCodeSigningTest {

    private static final String FUNCTION_NAME = "sdk-test-code-signing-fn";
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
    void getFunctionCodeSigningConfig_existingFunction_returnsEmptyArn() {
        GetFunctionCodeSigningConfigResponse response = lambda.getFunctionCodeSigningConfig(
                GetFunctionCodeSigningConfigRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .build());

        assertThat(response.functionName()).isEqualTo(FUNCTION_NAME);
        assertThat(response.codeSigningConfigArn()).isNullOrEmpty();
    }

    @Test
    @Order(2)
    void getFunctionCodeSigningConfig_unknownFunction_throws404() {
        assertThatThrownBy(() -> lambda.getFunctionCodeSigningConfig(
                GetFunctionCodeSigningConfigRequest.builder()
                        .functionName("does-not-exist")
                        .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

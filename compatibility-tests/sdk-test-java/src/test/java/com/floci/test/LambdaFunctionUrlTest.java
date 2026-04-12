package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.lambda.model.Runtime;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Lambda Function URL Config")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaFunctionUrlTest {

    private static LambdaClient lambda;
    private static final String FUNCTION_NAME = "sdk-url-test-fn";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";

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
                lambda.deleteFunctionUrlConfig(DeleteFunctionUrlConfigRequest.builder()
                        .functionName(FUNCTION_NAME).build());
            } catch (Exception ignored) {}
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder()
                        .functionName(FUNCTION_NAME).build());
            } catch (Exception ignored) {}
            lambda.close();
        }
    }

    @Test
    @Order(1)
    void createFunctionUrlConfig() {
        CreateFunctionUrlConfigResponse response = lambda.createFunctionUrlConfig(
                CreateFunctionUrlConfigRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .authType(FunctionUrlAuthType.NONE)
                        .build());

        assertThat(response.functionUrl()).isNotBlank();
        assertThat(response.functionArn()).isNotNull().contains(FUNCTION_NAME);
        assertThat(response.authTypeAsString()).isEqualTo("NONE");
        assertThat(response.invokeMode()).isNotNull();
        assertThat(response.creationTime()).isNotBlank();
    }

    @Test
    @Order(2)
    void getFunctionUrlConfig() {
        GetFunctionUrlConfigResponse response = lambda.getFunctionUrlConfig(
                GetFunctionUrlConfigRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .build());

        assertThat(response.functionUrl()).isNotBlank();
        assertThat(response.functionArn()).isNotNull().contains(FUNCTION_NAME);
        assertThat(response.authTypeAsString()).isEqualTo("NONE");
        assertThat(response.creationTime()).isNotBlank();
        assertThat(response.lastModifiedTime()).isNotBlank();
    }

    @Test
    @Order(3)
    void updateFunctionUrlConfig() {
        UpdateFunctionUrlConfigResponse response = lambda.updateFunctionUrlConfig(
                UpdateFunctionUrlConfigRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .authType(FunctionUrlAuthType.AWS_IAM)
                        .build());

        assertThat(response.authTypeAsString()).isEqualTo("AWS_IAM");
        assertThat(response.functionUrl()).isNotBlank();
        assertThat(response.functionArn()).isNotNull().contains(FUNCTION_NAME);
        assertThat(response.lastModifiedTime()).isNotBlank();
    }

    @Test
    @Order(4)
    void getFunctionUrlConfigAfterUpdate() {
        GetFunctionUrlConfigResponse response = lambda.getFunctionUrlConfig(
                GetFunctionUrlConfigRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .build());

        assertThat(response.authTypeAsString()).isEqualTo("AWS_IAM");
    }

    @Test
    @Order(5)
    void deleteFunctionUrlConfig() {
        lambda.deleteFunctionUrlConfig(DeleteFunctionUrlConfigRequest.builder()
                .functionName(FUNCTION_NAME)
                .build());

        assertThatThrownBy(() -> lambda.getFunctionUrlConfig(
                GetFunctionUrlConfigRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(6)
    void getFunctionUrlConfigForNonExistentFunction() {
        assertThatThrownBy(() -> lambda.getFunctionUrlConfig(
                GetFunctionUrlConfigRequest.builder()
                        .functionName("does-not-exist-" + TestFixtures.uniqueName())
                        .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(7)
    void createFunctionUrlConfigConflict() {
        // Re-create URL config
        lambda.createFunctionUrlConfig(CreateFunctionUrlConfigRequest.builder()
                .functionName(FUNCTION_NAME)
                .authType(FunctionUrlAuthType.NONE)
                .build());

        // Creating again should fail with conflict
        assertThatThrownBy(() -> lambda.createFunctionUrlConfig(
                CreateFunctionUrlConfigRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .authType(FunctionUrlAuthType.NONE)
                        .build()))
                .hasMessageContaining("already exists");
    }
}

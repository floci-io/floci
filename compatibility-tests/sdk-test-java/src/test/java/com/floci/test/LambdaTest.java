package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.lambda.model.Runtime;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Lambda")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaTest {

    private static LambdaClient lambda;
    private static final String FUNCTION_NAME = "sdk-test-fn";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";
    private static String functionArn;

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
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder()
                        .functionName("sdk-test-ruby-fn").build());
            } catch (Exception ignored) {}
            lambda.close();
        }
    }

    @Test
    @Order(1)
    void createFunction() {
        CreateFunctionResponse response = lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FUNCTION_NAME)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .timeout(30)
                .memorySize(256)
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip()))
                        .build())
                .build());

        functionArn = response.functionArn();
        assertThat(response.functionName()).isEqualTo(FUNCTION_NAME);
        assertThat(functionArn).isNotNull().contains(FUNCTION_NAME);
        assertThat(response.stateAsString()).isEqualTo("Active");
        assertThat(response.version()).isEqualTo("$LATEST");
    }

    @Test
    @Order(2)
    void getFunction() {
        GetFunctionResponse response = lambda.getFunction(
                GetFunctionRequest.builder().functionName(FUNCTION_NAME).build());

        assertThat(response.configuration().functionName()).isEqualTo(FUNCTION_NAME);
        assertThat(response.configuration().role()).isEqualTo(ROLE);
    }

    @Test
    @Order(3)
    void getFunctionConfiguration() {
        GetFunctionConfigurationResponse response = lambda.getFunctionConfiguration(
                GetFunctionConfigurationRequest.builder().functionName(FUNCTION_NAME).build());

        assertThat(response.timeout()).isEqualTo(30);
        assertThat(response.memorySize()).isEqualTo(256);
    }

    @Test
    @Order(4)
    void listFunctions() {
        ListFunctionsResponse response = lambda.listFunctions();

        assertThat(response.functions())
                .anyMatch(f -> FUNCTION_NAME.equals(f.functionName()));
    }

    @Test
    @Order(5)
    void invokeDryRun() {
        InvokeResponse response = lambda.invoke(InvokeRequest.builder()
                .functionName(FUNCTION_NAME)
                .invocationType(InvocationType.DRY_RUN)
                .payload(SdkBytes.fromUtf8String("{\"key\":\"value\"}"))
                .build());

        assertThat(response.statusCode()).isEqualTo(204);
    }

    @Test
    @Order(6)
    void invokeEventAsync() {
        InvokeResponse response = lambda.invoke(InvokeRequest.builder()
                .functionName(FUNCTION_NAME)
                .invocationType(InvocationType.EVENT)
                .payload(SdkBytes.fromUtf8String("{\"key\":\"async\"}"))
                .build());

        assertThat(response.statusCode()).isEqualTo(202);
    }

    @Test
    @Order(7)
    void updateFunctionCode() {
        UpdateFunctionCodeResponse response = lambda.updateFunctionCode(
                UpdateFunctionCodeRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip()))
                        .build());

        assertThat(response.functionName()).isEqualTo(FUNCTION_NAME);
        assertThat(response.revisionId()).isNotNull();
    }

    @Test
    @Order(8)
    void publishVersionAndListVersionsByFunction() {
        PublishVersionResponse v1 = lambda.publishVersion(PublishVersionRequest.builder()
                .functionName(FUNCTION_NAME)
                .description("v1")
                .build());

        assertThat(v1.version()).isEqualTo("1");
        assertThat(v1.description()).isEqualTo("v1");

        PublishVersionResponse v2 = lambda.publishVersion(PublishVersionRequest.builder()
                .functionName(FUNCTION_NAME)
                .description("v2")
                .build());

        assertThat(v2.version()).isEqualTo("2");
        assertThat(v2.description()).isEqualTo("v2");

        ListVersionsByFunctionResponse listVersions = lambda.listVersionsByFunction(
                ListVersionsByFunctionRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .build());

        assertThat(listVersions.versions()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(listVersions.versions())
                .anyMatch(v -> "$LATEST".equals(v.version()))
                .anyMatch(v -> "1".equals(v.version()))
                .anyMatch(v -> "2".equals(v.version()));
    }

    @Test
    @Order(9)
    void createFunctionDuplicateThrows409() {
        assertThatThrownBy(() -> lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FUNCTION_NAME)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip()))
                        .build())
                .build()))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    @Order(10)
    void getFunctionNonExistentThrows404() {
        assertThatThrownBy(() -> lambda.getFunction(GetFunctionRequest.builder()
                .functionName("does-not-exist").build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(11)
    void deleteFunction() {
        lambda.deleteFunction(DeleteFunctionRequest.builder()
                .functionName(FUNCTION_NAME).build());

        assertThatThrownBy(() -> lambda.getFunction(GetFunctionRequest.builder()
                .functionName(FUNCTION_NAME).build()))
                .isInstanceOf(ResourceNotFoundException.class);

        // Verify versions are also gone
        assertThatThrownBy(() -> lambda.listVersionsByFunction(
                ListVersionsByFunctionRequest.builder()
                        .functionName(FUNCTION_NAME).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(12)
    void rubyRuntimeSupport() {
        String rubyFn = "sdk-test-ruby-fn";

        CreateFunctionResponse response = lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(rubyFn)
                .runtime(Runtime.RUBY3_3)
                .role(ROLE)
                .handler("lambda_function.lambda_handler")
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.rubyZip()))
                        .build())
                .build());

        assertThat(response.functionName()).isEqualTo(rubyFn);
        assertThat(response.runtime()).isEqualTo(Runtime.RUBY3_3);

        // Cleanup
        lambda.deleteFunction(DeleteFunctionRequest.builder()
                .functionName(rubyFn).build());
    }
}

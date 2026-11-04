package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Lambda")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaTest {

    private static LambdaClient lambda;
    private static final String FUNCTION_NAME = "sdk-test-fn";
    private static final String FILE_SYSTEM_FUNCTION_NAME = "sdk-test-efs-fn";
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
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder()
                        .functionName("sdk-test-provided-fn").build());
            } catch (Exception ignored) {}
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder()
                        .functionName(FILE_SYSTEM_FUNCTION_NAME).build());
            } catch (Exception e) {
                System.err.println("Best-effort Lambda file-system test cleanup failed: " + e.getMessage());
            }
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

    // ─────────────────────────────────────────────────────────────────────────
    // Issue #339 — AddPermission / GetPolicy / RemovePermission
    // ─────────────────────────────────────────────────────────────────────────

    private static final String PERM_FN = "sdk-test-perm-fn";
    private static final String STMT_S3  = "AllowS3Invoke";
    private static final String STMT_SNS = "AllowSnsInvoke";

    @Test
    @Order(12)
    @DisplayName("#339 addPermission: returns 201 with Statement JSON")
    void addPermissionReturnsStatement() {
        // Create a dedicated function for permission tests
        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(PERM_FN)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip()))
                        .build())
                .build());

        AddPermissionResponse response = lambda.addPermission(AddPermissionRequest.builder()
                .functionName(PERM_FN)
                .statementId(STMT_S3)
                .action("lambda:InvokeFunction")
                .principal("s3.amazonaws.com")
                .sourceArn("arn:aws:s3:::my-bucket")
                .build());

        assertThat(response.statement()).isNotNull().isNotEmpty();
        assertThat(response.statement()).contains("AllowS3Invoke");
        assertThat(response.statement()).contains("s3.amazonaws.com");
    }

    @Test
    @Order(13)
    @DisplayName("#339 addPermission: duplicate StatementId returns 409")
    void addPermissionDuplicateStatementIdThrows409() {
        assertThatThrownBy(() -> lambda.addPermission(AddPermissionRequest.builder()
                .functionName(PERM_FN)
                .statementId(STMT_S3)
                .action("lambda:InvokeFunction")
                .principal("s3.amazonaws.com")
                .build()))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    @Order(14)
    @DisplayName("#339 getPolicy: returns stored policy with all statements")
    void getPolicyReturnsStoredStatements() {
        // Add a second statement
        lambda.addPermission(AddPermissionRequest.builder()
                .functionName(PERM_FN)
                .statementId(STMT_SNS)
                .action("lambda:InvokeFunction")
                .principal("sns.amazonaws.com")
                .build());

        GetPolicyResponse response = lambda.getPolicy(GetPolicyRequest.builder()
                .functionName(PERM_FN)
                .build());

        assertThat(response.policy()).isNotNull().isNotEmpty();
        assertThat(response.policy()).contains(STMT_S3);
        assertThat(response.policy()).contains(STMT_SNS);
        assertThat(response.revisionId()).isNotNull();
    }

    @Test
    @Order(15)
    @DisplayName("#339 removePermission: returns 204 and statement is gone from policy")
    void removePermissionRemovesStatement() {
        lambda.removePermission(RemovePermissionRequest.builder()
                .functionName(PERM_FN)
                .statementId(STMT_SNS)
                .build());

        GetPolicyResponse response = lambda.getPolicy(GetPolicyRequest.builder()
                .functionName(PERM_FN)
                .build());

        assertThat(response.policy()).contains(STMT_S3);
        assertThat(response.policy()).doesNotContain(STMT_SNS);
    }

    @Test
    @Order(16)
    @DisplayName("#339 getPolicy: function with no permissions returns 404")
    void getPolicyNoPermissionsThrows404() {
        // Remove remaining statement so policy is empty
        lambda.removePermission(RemovePermissionRequest.builder()
                .functionName(PERM_FN)
                .statementId(STMT_S3)
                .build());

        assertThatThrownBy(() -> lambda.getPolicy(GetPolicyRequest.builder()
                .functionName(PERM_FN)
                .build()))
                .isInstanceOf(ResourceNotFoundException.class);

        // Cleanup
        lambda.deleteFunction(DeleteFunctionRequest.builder()
                .functionName(PERM_FN).build());
    }

    @Test
    @Order(17)
    void providedRuntimeInvoke() {
        String providedFn = "sdk-test-provided-fn";

        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(providedFn)
                .runtime(Runtime.PROVIDED_AL2023)
                .role(ROLE)
                .handler("bootstrap")
                .timeout(30)
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.providedRuntimeZip()))
                        .build())
                .build());

        // RequestResponse invoke — the bootstrap shell script POSTs a
        // response via the Runtime API, so we should get a real payload
        // back rather than a timeout.
        InvokeResponse response = lambda.invoke(InvokeRequest.builder()
                .functionName(providedFn)
                .invocationType(InvocationType.REQUEST_RESPONSE)
                .payload(SdkBytes.fromUtf8String("{\"test\":true}"))
                .build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.functionError()).isNull();
        String payload = response.payload().asUtf8String();
        assertThat(payload).contains("hello from provided runtime");

        lambda.deleteFunction(DeleteFunctionRequest.builder()
                .functionName(providedFn).build());
    }

    @Test
    @Order(18)
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

    @Test
    @Order(19)
    void fileSystemConfigsRoundTripAndMountWithAwsSdk() {
        FileSystemConfig fileSystem = FileSystemConfig.builder()
                .arn("arn:aws:elasticfilesystem:us-east-1:000000000000:"
                        + "access-point/fsap-0123456789abcdef0")
                .localMountPath("/mnt/shared")
                .build();

        CreateFunctionResponse created = lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FILE_SYSTEM_FUNCTION_NAME)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .vpcConfig(VpcConfig.builder()
                        .subnetIds("subnet-0123456789abcdef0")
                        .securityGroupIds("sg-0123456789abcdef0")
                        .build())
                .fileSystemConfigs(fileSystem)
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.fileSystemZip()))
                        .build())
                .build());

        assertThat(created.fileSystemConfigs()).containsExactly(fileSystem);

        GetFunctionConfigurationResponse configuration = lambda.getFunctionConfiguration(
                GetFunctionConfigurationRequest.builder()
                        .functionName(FILE_SYSTEM_FUNCTION_NAME)
                        .build());
        assertThat(configuration.fileSystemConfigs()).containsExactly(fileSystem);

        InvokeResponse invocation = lambda.invoke(InvokeRequest.builder()
                .functionName(FILE_SYSTEM_FUNCTION_NAME)
                .invocationType(InvocationType.REQUEST_RESPONSE)
                .payload(SdkBytes.fromUtf8String("{\"value\":\"from-sdk\"}"))
                .build());
        assertThat(invocation.functionError()).isNull();
        assertThat(invocation.payload().asUtf8String())
                .contains("\"mounted\":true")
                .contains("\"value\":\"from-sdk\"");

        UpdateFunctionConfigurationResponse updated = lambda.updateFunctionConfiguration(
                UpdateFunctionConfigurationRequest.builder()
                        .functionName(FILE_SYSTEM_FUNCTION_NAME)
                        .fileSystemConfigs(List.of())
                        .build());
        assertThat(updated.fileSystemConfigs()).isEmpty();
    }
}

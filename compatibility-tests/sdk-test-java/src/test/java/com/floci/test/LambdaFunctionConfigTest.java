package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.*;

/**
 * Compatibility tests for #471 — UpdateFunctionConfiguration missing fields.
 *
 * Verifies that CreateFunction and UpdateFunctionConfiguration accept and round-trip
 * Architectures, EphemeralStorage, TracingConfig, DeadLetterConfig, Environment,
 * CodeSha256, and LastModified via the AWS SDK for Java v2.
 */
@DisplayName("#471 — FunctionConfiguration fields")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaFunctionConfigTest {

    private static LambdaClient lambda;
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";

    // Shared function used across ordered tests
    private static final String FN = TestFixtures.uniqueName("fn-config");
    private static String revisionId;

    @BeforeAll
    static void setup() {
        lambda = TestFixtures.lambdaClient();
    }

    @AfterAll
    static void cleanup() {
        if (lambda != null) {
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(FN).build());
            } catch (Exception ignored) {}
            lambda.close();
        }
    }

    // ─── CreateFunction ──────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("createFunction includes CodeSha256 and ISO-8601 LastModified in response")
    void createFunctionResponseHasCodeSha256AndLastModified() {
        CreateFunctionResponse resp = lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FN)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip()))
                        .build())
                .build());

        assertThat(resp.codeSha256())
                .as("CodeSha256 must be a non-empty Base64 string")
                .isNotNull().isNotEmpty();

        assertThat(resp.lastModified())
                .as("LastModified must be a non-null ISO-8601 string")
                .isNotNull().isNotEmpty();

        // Verify it parses as a valid ISO-8601 date-time with offset
        assertThatCode(() -> OffsetDateTime.parse(resp.lastModified(),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")))
                .as("LastModified must parse as yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                .doesNotThrowAnyException();

        revisionId = resp.revisionId();
    }

    @Test
    @Order(2)
    @DisplayName("getFunctionConfiguration returns default Architectures, EphemeralStorage, TracingConfig, LoggingConfig, and omits Environment")
    void getFunctionConfigurationHasDefaults() {
        GetFunctionConfigurationResponse resp = lambda.getFunctionConfiguration(
                GetFunctionConfigurationRequest.builder().functionName(FN).build());

        assertThat(resp.architectures())
                .as("default Architectures must be [x86_64]")
                .containsExactly(Architecture.X86_64);

        assertThat(resp.ephemeralStorage())
                .as("default EphemeralStorage must be present")
                .isNotNull();
        assertThat(resp.ephemeralStorage().size())
                .as("default EphemeralStorage.Size must be 512")
                .isEqualTo(512);

        assertThat(resp.tracingConfig())
                .as("TracingConfig must always be present")
                .isNotNull();
        assertThat(resp.tracingConfig().modeAsString())
                .as("default TracingConfig.Mode must be PassThrough")
                .isEqualTo("PassThrough");

        // lex00/floci#83: verified against a real AWS account 2026-08-20 that
        // GetFunctionConfiguration omits the Environment key entirely for a
        // function created with no Environment block - it is not present-but-
        // empty. terraform-provider-aws's read path relies on exactly this
        // (`if function.Environment != nil { ... }`), and a present-but-empty
        // Environment was making every module that never sets one (e.g.
        // terraform-aws-modules/terraform-aws-lambda's "simple" example)
        // replan a spurious "- environment {}" forever under plain terraform,
        // with no choudoufu involved.
        assertThat(resp.environment())
                .as("Environment must be absent for a function that was never configured with one")
                .isNull();

        // LoggingConfig must always be present (verified against a real AWS
        // account 2026-08-20, lex00/floci#83's uncontested second half),
        // defaulting to Text format and /aws/lambda/<function-name>.
        assertThat(resp.loggingConfig())
                .as("LoggingConfig must always be present in the response")
                .isNotNull();
        assertThat(resp.loggingConfig().logFormatAsString())
                .as("default LoggingConfig.LogFormat must be Text")
                .isEqualTo("Text");
        assertThat(resp.loggingConfig().logGroup())
                .as("default LoggingConfig.LogGroup must be /aws/lambda/<function-name>")
                .isEqualTo("/aws/lambda/" + FN);
    }

    // ─── UpdateFunctionConfiguration ─────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("updateFunctionConfiguration round-trips EphemeralStorage and TracingConfig")
    void updateFunctionConfigurationRoundTripsNewFields() {
        UpdateFunctionConfigurationResponse resp = lambda.updateFunctionConfiguration(
                UpdateFunctionConfigurationRequest.builder()
                        .functionName(FN)
                        .timeout(60)
                        .ephemeralStorage(EphemeralStorage.builder().size(1024).build())
                        .tracingConfig(TracingConfig.builder().mode(TracingMode.ACTIVE).build())
                        .build());

        assertThat(resp.ephemeralStorage().size())
                .as("EphemeralStorage.Size must be updated to 1024")
                .isEqualTo(1024);

        assertThat(resp.tracingConfig().modeAsString())
                .as("TracingConfig.Mode must be updated to Active")
                .isEqualTo("Active");

        assertThat(resp.timeout())
                .as("Timeout must be updated to 60")
                .isEqualTo(60);

        // Verify update persists via a subsequent get
        GetFunctionConfigurationResponse getResp = lambda.getFunctionConfiguration(
                GetFunctionConfigurationRequest.builder().functionName(FN).build());

        assertThat(getResp.ephemeralStorage().size()).isEqualTo(1024);
        assertThat(getResp.tracingConfig().modeAsString()).isEqualTo("Active");
    }

    @Test
    @Order(3)
    @DisplayName("createFunction with arm64 architecture round-trips via getFunction")
    void createFunctionWithArm64ArchitectureRoundTrips() {
        String armFn = TestFixtures.uniqueName("fn-arm64");
        try {
            CreateFunctionResponse resp = lambda.createFunction(CreateFunctionRequest.builder()
                    .functionName(armFn)
                    .runtime(Runtime.NODEJS20_X)
                    .role(ROLE)
                    .handler("index.handler")
                    .architectures(java.util.List.of(Architecture.ARM64))
                    .code(FunctionCode.builder()
                            .zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip()))
                            .build())
                    .build());

            assertThat(resp.architectures())
                    .as("createFunction must persist arm64 architecture")
                    .containsExactly(Architecture.ARM64);

            GetFunctionConfigurationResponse getResp = lambda.getFunctionConfiguration(
                    GetFunctionConfigurationRequest.builder().functionName(armFn).build());

            assertThat(getResp.architectures())
                    .as("getFunctionConfiguration must return arm64 architecture")
                    .containsExactly(Architecture.ARM64);
        } finally {
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(armFn).build());
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(4)
    @DisplayName("updateFunctionConfiguration with stale RevisionId returns 412")
    void updateFunctionConfigurationStaleRevisionIdThrows412() {
        assertThatThrownBy(() -> lambda.updateFunctionConfiguration(
                UpdateFunctionConfigurationRequest.builder()
                        .functionName(FN)
                        .timeout(10)
                        .revisionId("00000000-0000-0000-0000-000000000000")
                        .build()))
                .as("Stale RevisionId must throw PreconditionFailedException (412)")
                .isInstanceOf(PreconditionFailedException.class);
    }

    @Test
    @Order(5)
    @DisplayName("updateFunctionConfiguration with environment variables round-trips correctly")
    void updateFunctionConfigurationEnvironmentRoundTrips() {
        UpdateFunctionConfigurationResponse resp = lambda.updateFunctionConfiguration(
                UpdateFunctionConfigurationRequest.builder()
                        .functionName(FN)
                        .environment(Environment.builder()
                                .variables(java.util.Map.of("KEY_A", "value-a", "KEY_B", "value-b"))
                                .build())
                        .build());

        assertThat(resp.environment()).isNotNull();
        assertThat(resp.environment().variables())
                .containsEntry("KEY_A", "value-a")
                .containsEntry("KEY_B", "value-b");

        // Clear environment — response must still include the Environment block
        UpdateFunctionConfigurationResponse cleared = lambda.updateFunctionConfiguration(
                UpdateFunctionConfigurationRequest.builder()
                        .functionName(FN)
                        .environment(Environment.builder().build())
                        .build());

        assertThat(cleared.environment())
                .as("Environment block must be present even after clearing variables")
                .isNotNull();
    }

    @Test
    @Order(6)
    @DisplayName("ImageConfig.WorkingDirectory round-trips via create and get")
    void imageConfigWorkingDirectoryRoundTrips() {
        String imageFn = TestFixtures.uniqueName("fn-image-wd");
        try {
            CreateFunctionResponse createResp = lambda.createFunction(CreateFunctionRequest.builder()
                    .functionName(imageFn)
                    .packageType(software.amazon.awssdk.services.lambda.model.PackageType.IMAGE)
                    .role(ROLE)
                    .code(FunctionCode.builder()
                            .imageUri("000000000000.dkr.ecr.us-east-1.amazonaws.com/fake-repo:latest")
                            .build())
                    .imageConfig(software.amazon.awssdk.services.lambda.model.ImageConfig.builder()
                            .workingDirectory("/app")
                            .build())
                    .build());

            assertThat(createResp.imageConfigResponse().imageConfig().workingDirectory())
                    .as("CreateFunction response must include ImageConfig.WorkingDirectory")
                    .isEqualTo("/app");

            GetFunctionConfigurationResponse getResp = lambda.getFunctionConfiguration(
                    GetFunctionConfigurationRequest.builder().functionName(imageFn).build());

            assertThat(getResp.imageConfigResponse().imageConfig().workingDirectory())
                    .as("GetFunctionConfiguration must persist ImageConfig.WorkingDirectory")
                    .isEqualTo("/app");

            UpdateFunctionConfigurationResponse updateResp = lambda.updateFunctionConfiguration(
                    UpdateFunctionConfigurationRequest.builder()
                            .functionName(imageFn)
                            .imageConfig(software.amazon.awssdk.services.lambda.model.ImageConfig.builder()
                                    .workingDirectory("/updated")
                                    .build())
                            .build());

            assertThat(updateResp.imageConfigResponse().imageConfig().workingDirectory())
                    .as("UpdateFunctionConfiguration must update ImageConfig.WorkingDirectory")
                    .isEqualTo("/updated");
        } finally {
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(imageFn).build());
            } catch (Exception ignored) {}
        }
    }
}

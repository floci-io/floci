package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test for Lambda hot-reload (issue #553).
 *
 * <p>Creates a function via {@code S3Bucket=hot-reload, S3Key=/host/path}, verifies
 * it returns an initial response, then mutates the handler on disk and verifies the
 * next invocation picks up the change — without calling UpdateFunctionCode.
 *
 * <p>Requires Docker dispatch and that the host path is reachable by the Docker daemon.
 * Skipped automatically when Lambda invocation is unavailable.
 */
@DisplayName("Lambda hot-reload (issue #553)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaHotReloadTest {

    private static final String FUNCTION_NAME = "sdk-hot-reload-fn";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LambdaClient lambda;
    private static Path codeDir;

    @BeforeAll
    static void setup() throws IOException {
        assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "Lambda dispatch unavailable — skipping hot-reload test");

        lambda = TestFixtures.lambdaClient();

        // HOT_RELOAD_BASE_DIR is set in CI to a host-mounted volume so the Docker
        // daemon (on the host) can see the path. Unset means the test runs locally
        // where the system tmpdir is already on the Docker host.
        String baseDir = System.getenv("HOT_RELOAD_BASE_DIR");
        codeDir = baseDir != null
                ? Files.createTempDirectory(Path.of(baseDir), "floci-hot-reload-")
                : Files.createTempDirectory("floci-hot-reload-");

        writeHandler(codeDir, "v1");

        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FUNCTION_NAME)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .timeout(30)
                .code(FunctionCode.builder()
                        .s3Bucket("hot-reload")
                        .s3Key(codeDir.toAbsolutePath().toString())
                        .build())
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (lambda != null) {
            try { lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(FUNCTION_NAME).build()); } catch (Exception ignored) {}
            lambda.close();
        }
        if (codeDir != null) {
            try { Files.deleteIfExists(codeDir.resolve("index.js")); } catch (Exception ignored) {}
            try { Files.deleteIfExists(codeDir); } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(1)
    @DisplayName("Create hot-reload function succeeds with state Active")
    void createHotReloadFunction() {
        GetFunctionResponse fn = lambda.getFunction(
                GetFunctionRequest.builder().functionName(FUNCTION_NAME).build());
        assertThat(fn.configuration().stateAsString()).isEqualTo("Active");
        assertThat(fn.configuration().functionName()).isEqualTo(FUNCTION_NAME);
    }

    @Test
    @Order(2)
    @DisplayName("Invoke returns initial handler response (v1)")
    void invokeReturnsInitialVersion() throws Exception {
        InvokeResponse response = invoke();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.functionError()).isNullOrEmpty();

        JsonNode result = MAPPER.readTree(response.payload().asUtf8String());
        assertThat(result.path("version").asText())
                .as("First invocation should return v1")
                .isEqualTo("v1");
    }

    @Test
    @Order(3)
    @DisplayName("Mutate handler on disk — next invocation returns updated response (v2) without redeployment")
    void mutateHandlerOnDisk_invokeReturnsUpdatedVersion_withoutRedeploy() throws Exception {
        writeHandler(codeDir, "v2");

        InvokeResponse response = invoke();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.functionError()).isNullOrEmpty();

        JsonNode result = MAPPER.readTree(response.payload().asUtf8String());
        assertThat(result.path("version").asText())
                .as("After overwriting index.js on disk, next invocation must return v2 without UpdateFunctionCode")
                .isEqualTo("v2");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void writeHandler(Path dir, String version) throws IOException {
        String code = """
                exports.handler = async () => ({ version: "%s" });
                """.formatted(version);
        Files.writeString(dir.resolve("index.js"), code);
    }

    private InvokeResponse invoke() {
        return lambda.invoke(InvokeRequest.builder()
                .functionName(FUNCTION_NAME)
                .invocationType(InvocationType.REQUEST_RESPONSE)
                .payload(SdkBytes.fromUtf8String("{}"))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)))
                .build());
    }
}

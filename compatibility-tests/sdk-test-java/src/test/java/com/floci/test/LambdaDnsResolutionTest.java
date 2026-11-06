package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that a Lambda container can reach S3 via virtual-hosted URL using
 * the embedded DNS server injected into the container's /etc/resolv.conf.
 *
 * Requires Docker dispatch (docker.sock mounted) — skipped automatically when
 * Lambda invocation is unavailable (CI without Docker, host-only mode, etc.).
 *
 * <p>The endpoint used inside the Lambda must match Floci's FLOCI_HOSTNAME so
 * that the embedded DNS resolves it and S3VirtualHostFilter recognises the
 * virtual-host prefix. Override via the {@code FLOCI_DNS_HOSTNAME} env var
 * (default: {@code floci}, matching the standard docker-compose.yml).
 */
@DisplayName("Lambda DNS — S3 virtual-host resolution from inside container")
class LambdaDnsResolutionTest {

    private static final String FUNCTION_NAME = "dns-probe-fn";
    private static final String BUCKET = "dns-probe-bucket";
    private static final String KEY = "hello.txt";
    private static final String OBJECT_BODY = "dns-resolution-ok";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";

    // The hostname Floci is reachable at from *inside* a Lambda container.
    // Must match FLOCI_HOSTNAME so the embedded DNS resolves *.{hostname} and
    // S3VirtualHostFilter extracts the bucket from the virtual-hosted Host header.
    private static final String FLOCI_DNS_ENDPOINT =
            Optional.ofNullable(System.getenv("FLOCI_DNS_HOSTNAME"))
                    .filter(h -> !h.isBlank())
                    .map(h -> h + ":4566")
                    .orElse("floci:4566");

    private static LambdaClient lambda;
    private static S3Client s3;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setup() {
        assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "Lambda dispatch unavailable — skipping DNS resolution test");

        lambda = TestFixtures.lambdaClient();
        s3 = TestFixtures.s3Client();

        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        s3.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key(KEY).build(),
                RequestBody.fromString(OBJECT_BODY));

        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FUNCTION_NAME)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .timeout(30)
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.s3VirtualHostFetchZip()))
                        .build())
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (lambda != null) {
            try { lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(FUNCTION_NAME).build()); } catch (Exception ignored) {}
            lambda.close();
        }
        if (s3 != null) {
            try { s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(KEY).build()); } catch (Exception ignored) {}
            try { s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build()); } catch (Exception ignored) {}
            s3.close();
        }
    }

    @Test
    @DisplayName("Lambda fetches S3 object via virtual-hosted URL using embedded DNS")
    void lambdaResolvesS3ViaVirtualHostedUrl() throws Exception {
        String payload = MAPPER.writeValueAsString(
                MAPPER.createObjectNode()
                        .put("bucket", BUCKET)
                        .put("key", KEY)
                        .put("endpoint", FLOCI_DNS_ENDPOINT));

        InvokeResponse response = lambda.invoke(InvokeRequest.builder()
                .functionName(FUNCTION_NAME)
                .invocationType(InvocationType.REQUEST_RESPONSE)
                .payload(SdkBytes.fromUtf8String(payload))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)))
                .build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.functionError()).isNullOrEmpty();

        String responseBody = response.payload().asUtf8String();
        JsonNode result = MAPPER.readTree(responseBody);

        assertThat(result.path("statusCode").asInt())
                .as("S3 virtual-host HTTP status from inside Lambda container")
                .isEqualTo(200);
        assertThat(result.path("body").asText())
                .as("S3 object body fetched via virtual-hosted URL")
                .contains(OBJECT_BODY);
    }
}

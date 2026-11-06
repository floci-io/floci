package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.net.InetAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that a Lambda container can resolve a public hostname through Floci's embedded DNS
 * server, which forwards non-Floci names to its upstream / configured fallback resolvers.
 *
 * <p>Regression for <a href="https://github.com/floci-io/floci/issues/1110">#1110</a>: public
 * lookups failed with {@code ENOTFOUND}/{@code EBUSY} because the forwarder truncated responses
 * and spawned containers had no fallback resolver.
 *
 * <p>Requires Docker dispatch (docker.sock mounted) and outbound internet; self-skips otherwise.
 */
@DisplayName("Lambda DNS — public hostname resolution from inside container")
class LambdaPublicDnsResolutionTest {

    private static final String FUNCTION_NAME = "public-dns-probe-fn";
    private static final String PUBLIC_HOST = "example.com";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";

    private static LambdaClient lambda;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setup() {
        assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "Lambda dispatch unavailable — skipping public DNS resolution test");
        assumeTrue(hasInternet(),
                "No outbound internet on the test host — skipping public DNS resolution test");

        lambda = TestFixtures.lambdaClient();
        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FUNCTION_NAME)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .timeout(30)
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.publicDnsLookupZip()))
                        .build())
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (lambda != null) {
            try { lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(FUNCTION_NAME).build()); } catch (Exception ignored) {}
            lambda.close();
        }
    }

    @Test
    @DisplayName("Lambda resolves a public hostname via embedded DNS forwarding")
    void lambdaResolvesPublicHostname() throws Exception {
        String payload = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("host", PUBLIC_HOST));

        InvokeResponse response = lambda.invoke(InvokeRequest.builder()
                .functionName(FUNCTION_NAME)
                .invocationType(InvocationType.REQUEST_RESPONSE)
                .payload(SdkBytes.fromUtf8String(payload))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)))
                .build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.functionError())
                .as("public DNS lookup must not fail (ENOTFOUND/EBUSY) inside the Lambda container")
                .isNullOrEmpty();

        JsonNode result = MAPPER.readTree(response.payload().asUtf8String());
        assertThat(result.path("resolved").asBoolean()).isTrue();
        assertThat(result.path("address").asText())
                .as("resolved public IP address")
                .isNotBlank();
    }

    private static boolean hasInternet() {
        try {
            InetAddress.getByName(PUBLIC_HOST);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

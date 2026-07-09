package io.github.hectorvent.floci.services.bedrockruntime;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies a syntactically-invalid {@code proxy.url} surfaces as a clean
 * 400 ValidationException rather than an unmapped IllegalArgumentException.
 */
@QuarkusTest
@TestProfile(BedrockProxyInvalidUrlIntegrationTest.InvalidUrlProfile.class)
class BedrockProxyInvalidUrlIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request";

    @Test
    void converse_malformedProxyUrl_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """)
        .when()
            .post("/model/anthropic.claude-3-haiku-20240307-v1:0/converse")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    public static final class InvalidUrlProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.bedrock-runtime.backend", "proxy",
                    "floci.services.bedrock-runtime.proxy.url", "not a valid url",
                    "floci.services.bedrock-runtime.proxy.passthrough", "true"
            );
        }
    }
}

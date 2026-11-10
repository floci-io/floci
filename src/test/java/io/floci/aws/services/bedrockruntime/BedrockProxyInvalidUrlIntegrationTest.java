package io.floci.aws.services.bedrockruntime;

import io.floci.aws.testing.IndependentServiceSettingsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies a syntactically-invalid {@code proxy.url} surfaces as a clean
 * 400 ValidationException rather than an unmapped IllegalArgumentException.
 *
 * <p>The proxy backend, its invalid URL and the model passthrough that gets a call as far as the
 * URL come from {@link IndependentServiceSettingsProfile}, which this class shares with other
 * classes that each call a different service.
 */
@QuarkusTest
@TestProfile(IndependentServiceSettingsProfile.class)
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
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("proxy.url is not a valid URL"));
    }
}

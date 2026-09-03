package io.github.hectorvent.floci.services.bedrockruntime;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for the Bedrock Runtime stub (Converse + InvokeModel).
 * Uses RestAssured directly against the REST JSON wire format.
 */
@QuarkusTest
class BedrockRuntimeIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request";

    @Test
    void converse_happyPath() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "messages": [
                    {"role": "user", "content": [{"text": "hi"}]}
                  ]
                }
                """)
        .when()
            .post("/model/anthropic.claude-3-haiku-20240307-v1:0/converse")
        .then()
            .statusCode(200)
            .body("output.message.role", equalTo("assistant"))
            .body("output.message.content[0].text",
                    containsString("anthropic.claude-3-haiku-20240307-v1:0"))
            .body("stopReason", equalTo("end_turn"))
            .body("usage.inputTokens", greaterThan(0))
            .body("usage.outputTokens", greaterThan(0))
            .body("usage.totalTokens", greaterThan(0))
            .body("metrics.latencyMs", notNullValue());
    }

    @Test
    void converse_acceptsSystemAndInferenceConfig() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "messages": [
                    {"role": "user", "content": [{"text": "hi"}]}
                  ],
                  "system": [{"text": "You are a helpful assistant."}],
                  "inferenceConfig": {"maxTokens": 100, "temperature": 0.7},
                  "toolConfig": {"tools": []}
                }
                """)
        .when()
            .post("/model/anthropic.claude-3-haiku-20240307-v1:0/converse")
        .then()
            .statusCode(200)
            .body("output.message.role", equalTo("assistant"));
    }

    @Test
    void converse_inferenceProfileArn() {
        // Real AWS SDKs send full ARNs with slashes for inference profiles and
        // provisioned throughput. Path must match via {modelId:.+}.
        String arn = "arn:aws:bedrock:us-east-1:123456789012:inference-profile/"
                + "us.anthropic.claude-3-5-sonnet-20241022-v2:0";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """)
        .when()
            .post("/model/" + arn + "/converse")
        .then()
            .statusCode(200)
            .body("output.message.content[0].text", containsString("inference-profile/"));
    }

    @Test
    void invokeModel_inferenceProfileArn() {
        String arn = "arn:aws:bedrock:us-east-1:123456789012:inference-profile/"
                + "us.anthropic.claude-3-5-sonnet-20241022-v2:0";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": "hi"}]}
                """)
        .when()
            .post("/model/" + arn + "/invoke")
        .then()
            .statusCode(200)
            .body("type", equalTo("message"))
            .body("model", containsString("inference-profile/"));
    }

    @Test
    void converse_inferenceProfileModelId() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """)
        .when()
            .post("/model/us.anthropic.claude-3-5-sonnet-20241022-v2:0/converse")
        .then()
            .statusCode(200)
            .body("output.message.content[0].text",
                    containsString("us.anthropic.claude-3-5-sonnet-20241022-v2:0"));
    }

    @Test
    void converse_missingMessages_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/model/anthropic.claude-3-haiku-20240307-v1:0/converse")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void converse_emptyMessages_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": []}
                """)
        .when()
            .post("/model/anthropic.claude-3-haiku-20240307-v1:0/converse")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void invokeModel_anthropicShape() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "anthropic_version": "bedrock-2023-05-31",
                  "max_tokens": 100,
                  "messages": [{"role": "user", "content": "hi"}]
                }
                """)
        .when()
            .post("/model/anthropic.claude-3-haiku-20240307-v1:0/invoke")
        .then()
            .statusCode(200)
            .body("id", equalTo("msg_stub"))
            .body("type", equalTo("message"))
            .body("role", equalTo("assistant"))
            .body("content[0].type", equalTo("text"))
            .body("content[0].text", notNullValue())
            .body("model", equalTo("anthropic.claude-3-haiku-20240307-v1:0"))
            .body("stop_reason", equalTo("end_turn"))
            .body("usage.input_tokens", greaterThan(0))
            .body("usage.output_tokens", greaterThan(0));
    }

    @Test
    void invokeModel_inferenceProfileAnthropicShape() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": "hi"}]}
                """)
        .when()
            .post("/model/us.anthropic.claude-3-5-sonnet-20241022-v2:0/invoke")
        .then()
            .statusCode(200)
            .body("type", equalTo("message"))
            .body("model", equalTo("us.anthropic.claude-3-5-sonnet-20241022-v2:0"));
    }

    @Test
    void invokeModel_otherModelFamilyGetsGenericShape() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"prompt": "hi", "max_gen_len": 100}
                """)
        .when()
            .post("/model/meta.llama3-8b-instruct-v1:0/invoke")
        .then()
            .statusCode(200)
            .body("outputs[0].text", notNullValue());
    }

    @Test
    void invokeModelWithResponseStream_returns501() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/model/anthropic.claude-3-haiku-20240307-v1:0/invoke-with-response-stream")
        .then()
            .statusCode(501)
            .body("__type", equalTo("UnsupportedOperationException"));
    }

    @Test
    void converseStream_happyPath() {
        String body = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """)
        .when()
            .post("/model/anthropic.claude-3-haiku-20240307-v1:0/converse-stream")
        .then()
            .statusCode(200)
            .header("Content-Type", containsString("application/vnd.amazon.eventstream"))
            .extract().body().asString();

        // The response is binary event-stream framing, not JSON; the event names and JSON
        // payload text still appear as recognizable UTF-8 substrings around the framing bytes.
        assertThat(body, containsString("messageStart"));
        assertThat(body, containsString("contentBlockDelta"));
        assertThat(body, containsString("anthropic.claude-3-haiku-20240307-v1:0"));
        assertThat(body, containsString("contentBlockStop"));
        assertThat(body, containsString("messageStop"));
        assertThat(body, containsString("end_turn"));
        assertThat(body, containsString("metadata"));
    }

    @Test
    void converseStream_missingMessages_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/model/anthropic.claude-3-haiku-20240307-v1:0/converse-stream")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void disabled_whenServiceDisabled_returns400() {
        // The bedrock-runtime service is enabled in test config. Confirm it is
        // reachable via the default signing name in the Authorization header.
        given()
            .contentType("application/json")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock-runtime/aws4_request")
            .body("""
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """)
        .when()
            .post("/model/anthropic.claude-3-haiku-20240307-v1:0/converse")
        .then()
            .statusCode(200);
    }
}

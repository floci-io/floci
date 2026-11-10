package io.floci.aws.services.bedrockruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.floci.aws.testing.BedrockProxyBackendProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.testing.BedrockProxyBackendProfile.PORT;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that with {@code proxy.passthrough=true} the raw Bedrock model id is forwarded
 * as-is to the OpenAI-compatible backend, bypassing the mapping/default-model requirement.
 *
 * <p>Passthrough and the backend URL come from {@link BedrockProxyBackendProfile}, which this class
 * shares with the other proxy classes that talk to a stand-in. The raw model id is not in that
 * profile's mapping, so it reaches the backend through passthrough alone.
 */
@QuarkusTest
@TestProfile(BedrockProxyBackendProfile.class)
class BedrockProxyPassthroughIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request";
    private static final String RAW_MODEL_ID = "meta.llama3-8b-instruct-v1:0";

    @Inject
    ObjectMapper objectMapper;

    private HttpServer backend;
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        backend.createContext("/v1/chat/completions", exchange -> {
            receivedBody.set(exchange.getRequestBody().readAllBytes());

            byte[] resp = """
                {
                  "choices": [{
                    "finish_reason": "stop",
                    "message": {"role": "assistant", "content": "passthrough ok"}
                  }],
                  "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        backend.start();
    }

    @AfterEach
    void tearDown() {
        backend.stop(0);
    }

    @Test
    void converse_passthroughSendsRawModelId() throws IOException {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """)
        .when()
            .post("/model/" + RAW_MODEL_ID + "/converse")
        .then()
            .statusCode(200)
            .body("output.message.content[0].text", equalTo("passthrough ok"));

        JsonNode sentRequest = objectMapper.readTree(receivedBody.get());
        assertEquals(RAW_MODEL_ID, sentRequest.path("model").asText());
    }
}

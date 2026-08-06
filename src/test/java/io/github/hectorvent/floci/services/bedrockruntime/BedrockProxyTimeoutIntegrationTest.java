package io.github.hectorvent.floci.services.bedrockruntime;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies that a slow backend response is surfaced as ModelTimeoutException (408),
 * not the generic ModelErrorException (424) - the AWS SDKs' default retry strategy
 * treats 408 as retryable and 424 as terminal.
 */
@QuarkusTest
@TestProfile(BedrockProxyTimeoutIntegrationTest.SlowProxyBackendProfile.class)
class BedrockProxyTimeoutIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request";
    private static final int PORT = 18931;
    private static final String MAPPED_MODEL_ID = "anthropic.claude-3-haiku-20240307-v1:0";

    private HttpServer backend;

    @BeforeEach
    void setUp() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        backend.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            byte[] resp = "{}".getBytes(StandardCharsets.UTF_8);
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
    void converse_backendTimesOut_returnsModelTimeoutException() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(408)
            .body("__type", equalTo("ModelTimeoutException"));
    }

    public static final class SlowProxyBackendProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.bedrock-runtime.backend", "proxy",
                    "floci.services.bedrock-runtime.proxy.url", "http://127.0.0.1:" + PORT + "/v1",
                    "floci.services.bedrock-runtime.proxy.model-mapping",
                            MAPPED_MODEL_ID + "=claude-3-haiku",
                    "floci.services.bedrock-runtime.proxy.request-timeout-seconds", "1"
            );
        }
    }
}

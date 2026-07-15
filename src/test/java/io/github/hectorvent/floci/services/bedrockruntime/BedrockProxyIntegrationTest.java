package io.github.hectorvent.floci.services.bedrockruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Bedrock Runtime {@code proxy} backend translates Converse requests to
 * OpenAI Chat Completions and translates the response back to the Bedrock shape,
 * against a real (mock) OpenAI-compatible backend.
 */
@QuarkusTest
@TestProfile(BedrockProxyIntegrationTest.ProxyBackendProfile.class)
class BedrockProxyIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request";
    private static final int PORT = 18930;
    private static final String MAPPED_MODEL_ID = "anthropic.claude-3-haiku-20240307-v1:0";

    @Inject
    ObjectMapper objectMapper;

    private HttpServer backend;
    private final AtomicReference<String> nextResponseBody = new AtomicReference<>();
    private final AtomicReference<Integer> nextResponseStatus = new AtomicReference<>(200);
    private final AtomicReference<RecordedRequest> received = new AtomicReference<>();

    private record RecordedRequest(String path, Headers headers, byte[] body) {}

    @BeforeEach
    void setUp() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        backend.createContext("/v1/chat/completions", exchange -> {
            byte[] reqBody = exchange.getRequestBody().readAllBytes();
            received.set(new RecordedRequest(exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), reqBody));

            byte[] resp = nextResponseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(nextResponseStatus.get(), resp.length);
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
    void converse_basicTextResponse() {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": "Hello from the proxy backend!"}
              }],
              "usage": {"prompt_tokens": 7, "completion_tokens": 9, "total_tokens": 16}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200)
            .body("output.message.role", equalTo("assistant"))
            .body("output.message.content[0].text", equalTo("Hello from the proxy backend!"))
            .body("stopReason", equalTo("end_turn"))
            .body("usage.inputTokens", equalTo(7))
            .body("usage.outputTokens", equalTo(9))
            .body("usage.totalTokens", equalTo(16));
    }

    @Test
    void converse_toolCallsTranslateToToolUse() throws IOException {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "tool_calls",
                "message": {
                  "role": "assistant",
                  "tool_calls": [{
                    "id": "call_abc123",
                    "type": "function",
                    "function": {
                      "name": "get_weather",
                      "arguments": "{\\"city\\":\\"Berlin\\"}"
                    }
                  }]
                }
              }],
              "usage": {"prompt_tokens": 5, "completion_tokens": 8, "total_tokens": 13}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "messages": [{"role": "user", "content": [{"text": "weather in Berlin?"}]}],
                  "toolConfig": {
                    "tools": [{
                      "toolSpec": {
                        "name": "get_weather",
                        "description": "Get current weather for a city",
                        "inputSchema": {"json": {"type": "object", "properties": {"city": {"type": "string"}}}}
                      }
                    }]
                  }
                }
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200)
            .body("output.message.role", equalTo("assistant"))
            .body("output.message.content[0].toolUse.toolUseId", equalTo("call_abc123"))
            .body("output.message.content[0].toolUse.name", equalTo("get_weather"))
            .body("output.message.content[0].toolUse.input.city", equalTo("Berlin"))
            .body("stopReason", equalTo("tool_use"))
            .body("usage.inputTokens", equalTo(5))
            .body("usage.outputTokens", equalTo(8));

        RecordedRequest r = received.get();
        assertNotNull(r);
        assertEquals("Bearer test-key", r.headers().getFirst("Authorization"));
        JsonNode sentRequest = objectMapper.readTree(r.body());
        assertEquals("claude-3-haiku", sentRequest.path("model").asText());
        assertEquals("get_weather", sentRequest.path("tools").get(0).path("function").path("name").asText());
        assertEquals("object", sentRequest.path("tools").get(0).path("function").path("parameters").path("type").asText());
    }

    @Test
    void converse_mixedTextAndToolCallsPreservesBothBlocks() {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "tool_calls",
                "message": {
                  "role": "assistant",
                  "content": "Let me check the weather for you.",
                  "tool_calls": [{
                    "id": "call_abc123",
                    "type": "function",
                    "function": {
                      "name": "get_weather",
                      "arguments": "{\\"city\\":\\"Kyiv\\"}"
                    }
                  }]
                }
              }],
              "usage": {"prompt_tokens": 5, "completion_tokens": 8, "total_tokens": 13}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "messages": [{"role": "user", "content": [{"text": "weather in Kyiv?"}]}],
                  "toolConfig": {
                    "tools": [{
                      "toolSpec": {
                        "name": "get_weather",
                        "description": "Get current weather for a city",
                        "inputSchema": {"json": {"type": "object", "properties": {"city": {"type": "string"}}}}
                      }
                    }]
                  }
                }
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200)
            .body("output.message.content[0].text", equalTo("Let me check the weather for you."))
            .body("output.message.content[1].toolUse.toolUseId", equalTo("call_abc123"))
            .body("output.message.content[1].toolUse.name", equalTo("get_weather"))
            .body("output.message.content[1].toolUse.input.city", equalTo("Kyiv"))
            .body("stopReason", equalTo("tool_use"));
    }

    @Test
    void converse_toolCallsFinishReasonWithoutArray_fallsBackToText() {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "tool_calls",
                "message": {"role": "assistant"}
              }],
              "usage": {"prompt_tokens": 2, "completion_tokens": 2, "total_tokens": 4}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200)
            .body("stopReason", equalTo("end_turn"))
            .body("output.message.content[0].text", equalTo(""));
    }

    @Test
    void converse_arrayContentResponse_extractsText() {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": [{"type": "text", "text": "array-shaped reply"}]}
              }],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200)
            .body("output.message.content[0].text", equalTo("array-shaped reply"));
    }

    @Test
    void converse_multiTurnToolResult_forwardsAsOpenAiToolMessage() throws IOException {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": "it is 18C and sunny"}
              }],
              "usage": {"prompt_tokens": 3, "completion_tokens": 3, "total_tokens": 6}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "messages": [
                    {"role": "user", "content": [{"text": "weather in Berlin?"}]},
                    {"role": "assistant", "content": [{"toolUse": {"toolUseId": "call_1", "name": "get_weather", "input": {"city": "Berlin"}}}]},
                    {"role": "user", "content": [{"toolResult": {"toolUseId": "call_1", "content": [{"text": "18C sunny"}], "status": "success"}}]}
                  ]
                }
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200);

        JsonNode sentMessages = objectMapper.readTree(received.get().body()).path("messages");
        boolean hasAssistantToolCall = false;
        boolean hasToolResultMessage = false;
        for (JsonNode m : sentMessages) {
            if ("assistant".equals(m.path("role").asText()) && m.path("tool_calls").isArray()
                    && "get_weather".equals(m.path("tool_calls").get(0).path("function").path("name").asText())) {
                hasAssistantToolCall = true;
            }
            if ("tool".equals(m.path("role").asText()) && "call_1".equals(m.path("tool_call_id").asText())
                    && m.path("content").asText().contains("18C sunny")) {
                hasToolResultMessage = true;
            }
        }
        assertTrue(hasAssistantToolCall, "expected an assistant message with tool_calls forwarded");
        assertTrue(hasToolResultMessage, "expected a tool-role message with the toolResult content forwarded");
    }

    @Test
    void converse_assistantToolCallOnlyMessageHasNullContent() throws IOException {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": "ok"}
              }],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "messages": [
                    {"role": "user", "content": [{"text": "weather in Berlin?"}]},
                    {"role": "assistant", "content": [{"toolUse": {"toolUseId": "call_1", "name": "get_weather", "input": {"city": "Berlin"}}}]}
                  ]
                }
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200);

        JsonNode sentMessages = objectMapper.readTree(received.get().body()).path("messages");
        JsonNode assistantMessage = null;
        for (JsonNode m : sentMessages) {
            if ("assistant".equals(m.path("role").asText())) {
                assistantMessage = m;
            }
        }
        assertNotNull(assistantMessage, "expected an assistant message with the tool call");
        assertTrue(assistantMessage.path("content").isNull(),
                "content should be null (not empty string) when the message has only tool calls");
    }

    @Test
    void converse_proxyErrorBodyIsTruncated() {
        nextResponseBody.set("x".repeat(1000));
        nextResponseStatus.set(500);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(424)
            .body("message", org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.containsString("…"),
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("x".repeat(1000)))));
    }

    @Test
    void converse_omitsToolsWhenNoToolConfig() throws IOException {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": "no tools here"}
              }],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200);

        JsonNode sentRequest = objectMapper.readTree(received.get().body());
        assertTrue(sentRequest.path("tools").isMissingNode());
    }

    @Test
    void converse_forwardsForcedToolChoice() throws IOException {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "tool_calls",
                "message": {
                  "role": "assistant",
                  "tool_calls": [{
                    "id": "call_1",
                    "type": "function",
                    "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"Berlin\\"}"}
                  }]
                }
              }],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "messages": [{"role": "user", "content": [{"text": "weather in Berlin?"}]}],
                  "toolConfig": {
                    "tools": [{
                      "toolSpec": {
                        "name": "get_weather",
                        "inputSchema": {"json": {"type": "object", "properties": {"city": {"type": "string"}}}}
                      }
                    }],
                    "toolChoice": {"tool": {"name": "get_weather"}}
                  }
                }
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200);

        JsonNode sentRequest = objectMapper.readTree(received.get().body());
        assertEquals("function", sentRequest.path("tool_choice").path("type").asText());
        assertEquals("get_weather", sentRequest.path("tool_choice").path("function").path("name").asText());
    }

    @Test
    void converse_forwardsAnyToolChoiceAsRequired() throws IOException {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": "ok"}
              }],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "messages": [{"role": "user", "content": [{"text": "hi"}]}],
                  "toolConfig": {
                    "tools": [{
                      "toolSpec": {
                        "name": "get_weather",
                        "inputSchema": {"json": {"type": "object", "properties": {"city": {"type": "string"}}}}
                      }
                    }],
                    "toolChoice": {"any": {}}
                  }
                }
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200);

        JsonNode sentRequest = objectMapper.readTree(received.get().body());
        assertEquals("required", sentRequest.path("tool_choice").asText());
    }

    @Test
    void converse_omitsToolChoiceWhenNotSpecified() throws IOException {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": "ok"}
              }],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "messages": [{"role": "user", "content": [{"text": "hi"}]}],
                  "toolConfig": {
                    "tools": [{
                      "toolSpec": {
                        "name": "get_weather",
                        "inputSchema": {"json": {"type": "object", "properties": {"city": {"type": "string"}}}}
                      }
                    }]
                  }
                }
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200);

        JsonNode sentRequest = objectMapper.readTree(received.get().body());
        assertFalse(sentRequest.has("tool_choice"));
    }

    @Test
    void converse_forwardsInferenceConfig() throws IOException {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": "ok"}
              }],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "messages": [{"role": "user", "content": [{"text": "hi"}]}],
                  "inferenceConfig": {"maxTokens": 100, "temperature": 0.3, "topP": 0.9}
                }
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200);

        JsonNode sentRequest = objectMapper.readTree(received.get().body());
        assertEquals(100, sentRequest.path("max_tokens").asInt());
        assertEquals(0.3, sentRequest.path("temperature").asDouble());
        assertEquals(0.9, sentRequest.path("top_p").asDouble());
    }

    @Test
    void converse_omitsInferenceConfigFieldsWhenAbsent() throws IOException {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": "ok"}
              }],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200);

        JsonNode sentRequest = objectMapper.readTree(received.get().body());
        assertFalse(sentRequest.has("max_tokens"));
        assertFalse(sentRequest.has("temperature"));
        assertFalse(sentRequest.has("top_p"));
    }

    @Test
    void converse_nullOrWrongTypeInferenceConfigFieldsAreSkipped() throws IOException {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": "ok"}
              }],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "messages": [{"role": "user", "content": [{"text": "hi"}]}],
                  "inferenceConfig": {"maxTokens": null, "temperature": "high", "topP": true}
                }
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200);

        JsonNode sentRequest = objectMapper.readTree(received.get().body());
        assertFalse(sentRequest.has("max_tokens"));
        assertFalse(sentRequest.has("temperature"));
        assertFalse(sentRequest.has("top_p"));
    }

    @Test
    void converse_forwardsStopSequences() throws IOException {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": "ok"}
              }],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "messages": [{"role": "user", "content": [{"text": "hi"}]}],
                  "inferenceConfig": {"stopSequences": ["</tool>", "STOP"]}
                }
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200);

        JsonNode sentRequest = objectMapper.readTree(received.get().body());
        assertEquals("</tool>", sentRequest.path("stop").get(0).asText());
        assertEquals("STOP", sentRequest.path("stop").get(1).asText());
    }

    @Test
    void converse_nullOrMissingToolChoiceNameIsIgnored() throws IOException {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": "ok"}
              }],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "messages": [{"role": "user", "content": [{"text": "hi"}]}],
                  "toolConfig": {
                    "tools": [{
                      "toolSpec": {
                        "name": "get_weather",
                        "inputSchema": {"json": {"type": "object", "properties": {"city": {"type": "string"}}}}
                      }
                    }],
                    "toolChoice": {"tool": {}}
                  }
                }
                """)
        .when()
            .post("/model/" + MAPPED_MODEL_ID + "/converse")
        .then()
            .statusCode(200);

        JsonNode sentRequest = objectMapper.readTree(received.get().body());
        assertFalse(sentRequest.has("tool_choice"));
    }

    @Test
    void converse_noMappingNoDefaultNoPassthrough_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """)
        .when()
            .post("/model/unmapped.model-v1:0/converse")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    public static final class ProxyBackendProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.bedrock-runtime.backend", "proxy",
                    "floci.services.bedrock-runtime.proxy.url", "http://127.0.0.1:" + PORT + "/v1",
                    "floci.services.bedrock-runtime.proxy.api-key", "test-key",
                    "floci.services.bedrock-runtime.proxy.model-mapping",
                            MAPPED_MODEL_ID + "=claude-3-haiku"
            );
        }
    }
}

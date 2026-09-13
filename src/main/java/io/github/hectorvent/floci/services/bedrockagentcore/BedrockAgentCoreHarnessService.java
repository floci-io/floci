package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsEventStreamWriter;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@code InvokeHarness}: the AgentCore Harness data plane.
 *
 * <p>There is no agent loop here and no model. What is emulated is the wire contract an application
 * integrates against: the event-stream framing, the event sequence and the order they arrive in.
 * The assistant's reply echoes the caller's last user message, so a chat UI visibly works end to end
 * locally and a request that failed to parse is obvious rather than hidden behind a fixed string.
 *
 * <p>Not emulated: model inference, tool execution, skills and multi-turn iteration. A request may
 * carry {@code tools}, but no tool-use block is ever produced.
 */
@ApplicationScoped
public class BedrockAgentCoreHarnessService {

    private static final String ROLE_USER = "user";

    /**
     * Modelled constraints, from the service model rather than guessed. A harness ARN ends in the
     * same {@code name-<10 alphanumerics>} shape AgentCore uses for its other resource ids, and a
     * runtime session id has a documented minimum length that a short test value does not meet.
     */
    private static final Pattern HARNESS_ARN = Pattern.compile(
            "arn:([^:]+)?:bedrock-agentcore:[a-z0-9-]+:[0-9]{12}:harness/[a-zA-Z][a-zA-Z0-9_]{0,39}-[a-zA-Z0-9]{10}");
    private static final Pattern RUNTIME_SESSION_ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9-_]*");
    private static final int RUNTIME_SESSION_ID_MIN = 33;
    private static final int RUNTIME_SESSION_ID_MAX = 100;
    /** Chunked so consumers exercise incremental delivery rather than one blob. */
    private static final int CHUNK_SIZE = 24;

    private final ObjectMapper objectMapper;
    private final String echoPrefix;
    private final String emptyReply;

    @Inject
    public BedrockAgentCoreHarnessService(ObjectMapper objectMapper, EmulatorConfig config) {
        this.objectMapper = objectMapper;
        this.echoPrefix = config.services().bedrockAgentCore().harnessEchoPrefix();
        this.emptyReply = config.services().bedrockAgentCore().harnessEmptyReply();
    }

    /**
     * Validates the request and returns the stream writer.
     *
     * <p>Validation happens before any frame is written: once the stream is committed the status is
     * already 200 and the only way left to report a problem is an in-stream exception frame.
     */
    public Consumer<OutputStream> invokeHarness(String harnessArn, String runtimeSessionId, ObjectNode request) {
        // harnessArn rides in the query string and runtimeSessionId in a header, so neither is
        // read from the body. Confirmed against the SDK's own request bindings.
        requirePresent(harnessArn, "harnessArn");
        requirePresent(runtimeSessionId, "runtimeSessionId");
        if (!HARNESS_ARN.matcher(harnessArn).matches()) {
            throw new AwsException("ValidationException", "Invalid harness ARN format.", 400);
        }
        if (runtimeSessionId.length() < RUNTIME_SESSION_ID_MIN
                || runtimeSessionId.length() > RUNTIME_SESSION_ID_MAX
                || !RUNTIME_SESSION_ID.matcher(runtimeSessionId).matches()) {
            throw new AwsException("ValidationException",
                    "runtimeSessionId must be " + RUNTIME_SESSION_ID_MIN + " to " + RUNTIME_SESSION_ID_MAX
                            + " characters and match " + RUNTIME_SESSION_ID.pattern(), 400);
        }
        // The ARN's shape is checked but the harness itself is not resolved: the emulator models no
        // harness resource, so there is nothing to look it up in.

        // messages is a required member. An empty array is a legitimate request, but an omitted or
        // non-array member is not, so the two must not collapse into the same canned reply.
        JsonNode messages = request.path("messages");
        if (!messages.isArray()) {
            throw new AwsException("ValidationException", "messages is required", 400);
        }

        String reply = buildReply(messages);
        return output -> writeStream(output, reply);
    }

    private String buildReply(JsonNode messages) {
        String lastUserText = lastUserText(messages);
        // An empty messages array is legitimate: a caller whose memory already holds the current
        // turn sends none. Answer rather than fail.
        return lastUserText.isBlank() ? emptyReply : echoPrefix + lastUserText;
    }

    /** Concatenates the text blocks of the last {@code user} message. */
    private static String lastUserText(JsonNode messages) {
        if (!messages.isArray()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            if (!ROLE_USER.equalsIgnoreCase(message.path("role").asText(""))) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode block : message.path("content")) {
                String blockText = block.path("text").asText("");
                if (!blockText.isEmpty()) {
                    text.append(blockText);
                }
            }
            return text.toString();
        }
        return "";
    }

    private void writeStream(OutputStream output, String reply) {
        AwsEventStreamWriter.writeEvent(objectMapper, output, "messageStart",
                objectMapper.createObjectNode().put("role", "assistant"));

        ObjectNode contentBlockStart = objectMapper.createObjectNode();
        contentBlockStart.put("contentBlockIndex", 0);
        AwsEventStreamWriter.writeEvent(objectMapper, output, "contentBlockStart", contentBlockStart);

        for (String chunk : chunk(reply)) {
            ObjectNode delta = objectMapper.createObjectNode();
            delta.put("contentBlockIndex", 0);
            delta.putObject("delta").put("text", chunk);
            AwsEventStreamWriter.writeEvent(objectMapper, output, "contentBlockDelta", delta);
        }

        AwsEventStreamWriter.writeEvent(objectMapper, output, "contentBlockStop",
                objectMapper.createObjectNode().put("contentBlockIndex", 0));
        AwsEventStreamWriter.writeEvent(objectMapper, output, "messageStop",
                objectMapper.createObjectNode().put("stopReason", "end_turn"));

        ObjectNode metadata = objectMapper.createObjectNode();
        // Token counts are a stand-in: nothing is tokenised, so they are derived from the text.
        int outputTokens = Math.max(1, reply.length() / 4);
        metadata.putObject("usage")
                .put("inputTokens", outputTokens)
                .put("outputTokens", outputTokens)
                .put("totalTokens", outputTokens * 2);
        metadata.putObject("metrics").put("latencyMs", 1);
        AwsEventStreamWriter.writeEvent(objectMapper, output, "metadata", metadata);
    }

    private static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
            chunks.add(text.substring(i, Math.min(text.length(), i + CHUNK_SIZE)));
        }
        return chunks.isEmpty() ? List.of("") : chunks;
    }

    private static void requirePresent(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationException", field + " is required", 400);
        }
    }
}

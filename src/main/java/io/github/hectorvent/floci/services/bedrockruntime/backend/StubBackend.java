package io.github.hectorvent.floci.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Dummy response builder for Bedrock Runtime. Stateless.
 * No real model inference: returns a fixed assistant turn plus token usage metadata.
 */
@ApplicationScoped
public class StubBackend implements BedrockBackend {

    private final ObjectMapper objectMapper;

    @Inject
    public StubBackend(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ObjectNode converse(String modelId, ObjectNode bedrockRequest) {
        ObjectNode root = objectMapper.createObjectNode();

        ObjectNode output = root.putObject("output");
        ObjectNode message = output.putObject("message");
        message.put("role", "assistant");
        ArrayNode content = message.putArray("content");
        ObjectNode textBlock = content.addObject();
        textBlock.put("text", "Floci stub response for model=" + modelId);

        root.put("stopReason", "end_turn");

        ObjectNode usage = root.putObject("usage");
        usage.put("inputTokens", 10);
        usage.put("outputTokens", 12);
        usage.put("totalTokens", 22);

        ObjectNode metrics = root.putObject("metrics");
        metrics.put("latencyMs", 1);

        return root;
    }

    @Override
    public byte[] invokeModel(String modelId, byte[] body) {
        ObjectNode root = objectMapper.createObjectNode();
        String lower = modelId == null ? "" : modelId.toLowerCase();
        if (lower.startsWith("anthropic.") || lower.contains(".anthropic.")) {
            root.put("id", "msg_stub");
            root.put("type", "message");
            root.put("role", "assistant");
            ArrayNode content = root.putArray("content");
            ObjectNode block = content.addObject();
            block.put("type", "text");
            block.put("text", "Floci stub response");
            root.put("model", modelId);
            root.put("stop_reason", "end_turn");
            ObjectNode usage = root.putObject("usage");
            usage.put("input_tokens", 10);
            usage.put("output_tokens", 12);
        } else {
            // Generic minimal shape for Meta, Mistral, Titan and others.
            // Bedrock returns provider-specific bodies; callers parse by model family.
            ArrayNode outputs = root.putArray("outputs");
            ObjectNode item = outputs.addObject();
            item.put("text", "Floci stub response");
        }
        try {
            return objectMapper.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize InvokeModel response", e);
        }
    }
}

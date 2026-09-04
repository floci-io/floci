package io.github.hectorvent.floci.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates between the Bedrock Runtime Converse wire shape and the OpenAI
 * Chat Completions wire shape used by Ollama, OpenRouter, LiteLLM, vLLM, etc.
 */
final class BedrockOpenAiTranslator {

    private static final Logger LOG = Logger.getLogger(BedrockOpenAiTranslator.class);

    private BedrockOpenAiTranslator() {
    }

    static ObjectNode toOpenAiRequest(ObjectMapper mapper, ObjectNode bedrockRequest, String resolvedModel, boolean stream) {
        ObjectNode openAi = mapper.createObjectNode();
        openAi.put("model", resolvedModel);
        openAi.put("stream", stream);
        if (stream) {
            // Not all OpenAI-compatible backends honor this, but the ones that do (including
            // OpenAI itself) only include token usage in the final chunk when asked for it.
            openAi.putObject("stream_options").put("include_usage", true);
        }
        ArrayNode openAiMessages = openAi.putArray("messages");

        JsonNode system = bedrockRequest.path("system");
        if (system.isArray()) {
            for (JsonNode block : system) {
                String text = block.path("text").asText(null);
                if (text != null) {
                    ObjectNode msg = openAiMessages.addObject();
                    msg.put("role", "system");
                    msg.put("content", text);
                }
            }
        }

        JsonNode messages = bedrockRequest.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                translateMessage(mapper, openAiMessages, message);
            }
        }

        JsonNode toolConfig = bedrockRequest.path("toolConfig");
        JsonNode tools = toolConfig.path("tools");
        if (tools.isArray() && !tools.isEmpty()) {
            ArrayNode openAiTools = openAi.putArray("tools");
            for (JsonNode tool : tools) {
                JsonNode toolSpec = tool.path("toolSpec");
                if (!toolSpec.isObject()) {
                    continue;
                }
                ObjectNode function = putFunctionRef(openAiTools.addObject(), toolSpec.path("name").asText(""));
                if (toolSpec.hasNonNull("description")) {
                    function.put("description", toolSpec.path("description").asText());
                }
                JsonNode schema = toolSpec.path("inputSchema").path("json");
                if (schema.isObject()) {
                    function.set("parameters", schema.deepCopy());
                }
            }

            // "auto" needs no explicit output: it's OpenAI's default whenever tools are present.
            JsonNode toolChoice = toolConfig.path("toolChoice");
            if (toolChoice.hasNonNull("any")) {
                openAi.put("tool_choice", "required");
            } else if (toolChoice.hasNonNull("tool")) {
                String toolName = toolChoice.path("tool").path("name").asText("");
                if (!toolName.isBlank()) {
                    putFunctionRef(openAi.putObject("tool_choice"), toolName);
                }
            }
        }

        JsonNode inferenceConfig = bedrockRequest.path("inferenceConfig");
        JsonNode maxTokens = inferenceConfig.path("maxTokens");
        if (maxTokens.isNumber()) {
            openAi.put("max_tokens", maxTokens.asInt());
        }
        JsonNode temperature = inferenceConfig.path("temperature");
        if (temperature.isNumber()) {
            openAi.put("temperature", temperature.asDouble());
        }
        JsonNode topP = inferenceConfig.path("topP");
        if (topP.isNumber()) {
            openAi.put("top_p", topP.asDouble());
        }
        JsonNode stopSequences = inferenceConfig.path("stopSequences");
        if (stopSequences.isArray() && !stopSequences.isEmpty()) {
            openAi.set("stop", stopSequences.deepCopy());
        }

        return openAi;
    }

    /** Builds the OpenAI {@code {type: "function", function: {name}}} shape shared by tools[] entries and tool_choice. */
    private static ObjectNode putFunctionRef(ObjectNode target, String name) {
        target.put("type", "function");
        return target.putObject("function").put("name", name);
    }

    /**
     * A Bedrock message's content[] blocks map to OpenAI in three different ways:
     * plain text accumulates into the message's content string, toolUse blocks become
     * entries in that same message's tool_calls[], and toolResult blocks become their
     * own standalone OpenAI message with role=tool — so one Bedrock message can expand
     * into zero, one, or several OpenAI messages.
     */
    private static void translateMessage(ObjectMapper mapper, ArrayNode openAiMessages, JsonNode message) {
        String role = message.path("role").asText("user");
        JsonNode content = message.path("content");
        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = null;

        if (content.isArray()) {
            for (JsonNode block : content) {
                if (block.has("toolResult")) {
                    JsonNode toolResult = block.path("toolResult");
                    ObjectNode toolMsg = openAiMessages.addObject();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", toolResult.path("toolUseId").asText(""));
                    toolMsg.put("content", extractToolResultText(toolResult.path("content")));
                    continue;
                }
                if (block.has("toolUse")) {
                    JsonNode toolUse = block.path("toolUse");
                    if (toolCalls == null) {
                        toolCalls = mapper.createArrayNode();
                    }
                    ObjectNode toolCall = toolCalls.addObject();
                    toolCall.put("id", toolUse.path("toolUseId").asText(""));
                    toolCall.put("type", "function");
                    ObjectNode function = toolCall.putObject("function");
                    function.put("name", toolUse.path("name").asText(""));
                    function.put("arguments", toJsonString(mapper, toolUse.path("input")));
                    continue;
                }
                String blockText = block.path("text").asText(null);
                if (blockText != null) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(blockText);
                }
            }
        }

        if (text.length() > 0 || toolCalls != null) {
            ObjectNode msg = openAiMessages.addObject();
            msg.put("role", role);
            if (text.length() > 0) {
                msg.put("content", text.toString());
            } else {
                // OpenAI's spec treats content as optional when tool_calls is present; some
                // strict backends reject an empty string here instead of null.
                msg.putNull("content");
            }
            if (toolCalls != null) {
                msg.set("tool_calls", toolCalls);
            }
        }
    }

    private static String extractToolResultText(JsonNode toolResultContent) {
        if (!toolResultContent.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : toolResultContent) {
            String text = part.path("text").asText(null);
            if (text == null && part.has("json")) {
                text = part.path("json").toString();
            }
            if (text != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private static String toJsonString(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            LOG.warnv("Failed to serialize toolUse input as JSON: {0}", e.getMessage());
            return "{}";
        }
    }

    static ObjectNode toBedrockResponse(ObjectMapper mapper, JsonNode openAiResponse, long latencyMs) {
        JsonNode choice = openAiResponse.path("choices").path(0);
        if (!choice.isObject()) {
            // A 2xx response with no choices at all (e.g. an error object the backend still
            // returned with a success status) is not a completion - don't fabricate an empty
            // assistant message out of it.
            throw new AwsException("ModelErrorException",
                    "Proxy backend's response had no choices: " + truncate(openAiResponse.toString(), 512), 424);
        }
        JsonNode message = choice.path("message");
        String finishReason = choice.path("finish_reason").asText("stop");
        JsonNode toolCallsNode = message.path("tool_calls");
        boolean hasToolCalls = toolCallsNode.isArray() && !toolCallsNode.isEmpty();

        ObjectNode root = mapper.createObjectNode();
        ObjectNode output = root.putObject("output");
        ObjectNode outMessage = output.putObject("message");
        outMessage.put("role", "assistant");
        ArrayNode content = outMessage.putArray("content");

        if (hasToolCalls) {
            // Some OpenAI-compatible backends emit assistant text alongside tool_calls (e.g. a
            // preamble like "Let me check that for you") — preserve it as a leading text block
            // instead of dropping it.
            String leadingText = extractMessageText(message);
            if (!leadingText.isBlank()) {
                content.addObject().put("text", leadingText);
            }
            for (JsonNode toolCall : toolCallsNode) {
                ObjectNode toolUse = content.addObject().putObject("toolUse");
                toolUse.put("toolUseId", toolCall.path("id").asText(""));
                JsonNode function = toolCall.path("function");
                toolUse.put("name", function.path("name").asText(""));
                toolUse.set("input", parseToolArguments(mapper, function.path("arguments").asText("{}")));
            }
        } else {
            content.addObject().put("text", extractMessageText(message));
        }

        // stopReason is derived from whether we actually emitted toolUse content, not from
        // finish_reason alone: a backend can report finish_reason=tool_calls without a usable
        // tool_calls array, which must not produce a Converse response claiming tool_use with
        // no toolUse blocks in it.
        root.put("stopReason", hasToolCalls ? "tool_use" : mapFinishReason(finishReason));

        JsonNode usage = openAiResponse.path("usage");
        ObjectNode usageOut = root.putObject("usage");
        usageOut.put("inputTokens", usage.path("prompt_tokens").asInt(0));
        usageOut.put("outputTokens", usage.path("completion_tokens").asInt(0));
        usageOut.put("totalTokens", usage.path("total_tokens").asInt(0));

        root.putObject("metrics").put("latencyMs", latencyMs);

        return root;
    }

    /**
     * Translates an OpenAI Chat Completions SSE stream body ({@code data: {...}\n\n} lines,
     * terminated by {@code data: [DONE]}) into the ordered Bedrock ConverseStream events
     * (messageStart, contentBlockDelta, contentBlockStop, messageStop, metadata), framed as
     * {@code application/vnd.amazon.eventstream} bytes via {@link BedrockStreamEncoder}.
     *
     * <p>Tool calls are accumulated across chunks and emitted as a single complete
     * contentBlockDelta once the stream ends, rather than as incremental argument
     * fragments - simpler than real Bedrock's fragment-by-fragment streaming, but
     * SDKs consume both the same way since they just concatenate delta.toolUse.input
     * across events for a given contentBlockIndex before parsing it as JSON.
     */
    static byte[] toBedrockStreamEvents(ObjectMapper mapper, String sseBody, long latencyMs) {
        List<BedrockStreamEncoder.Event> events = new ArrayList<>();
        events.add(new BedrockStreamEncoder.Event("messageStart", mapper.createObjectNode().put("role", "assistant")));

        StringBuilder text = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        String finishReason = "stop";
        boolean sawFinishReason = false;
        boolean sawDone = false;
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        int parsedChunks = 0;

        for (String rawLine : sseBody.split("\n")) {
            String line = rawLine.strip();
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).strip();
            if (data.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(data)) {
                sawDone = true;
                continue;
            }
            JsonNode chunk;
            try {
                chunk = mapper.readTree(data);
            } catch (Exception e) {
                LOG.warnv("Skipping malformed SSE chunk from proxy backend: {0}", e.getMessage());
                continue;
            }
            parsedChunks++;

            JsonNode usage = chunk.path("usage");
            if (usage.isObject()) {
                promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                totalTokens = usage.path("total_tokens").asInt(totalTokens);
            }

            JsonNode choice = chunk.path("choices").path(0);
            String reason = choice.path("finish_reason").asText(null);
            if (reason != null) {
                finishReason = reason;
                sawFinishReason = true;
            }

            JsonNode delta = choice.path("delta");
            String contentDelta = delta.path("content").asText(null);
            if (contentDelta != null && !contentDelta.isEmpty()) {
                text.append(contentDelta);
                ObjectNode deltaEvent = mapper.createObjectNode();
                deltaEvent.put("contentBlockIndex", 0);
                deltaEvent.putObject("delta").put("text", contentDelta);
                events.add(new BedrockStreamEncoder.Event("contentBlockDelta", deltaEvent));
            }

            JsonNode toolCallDeltas = delta.path("tool_calls");
            if (toolCallDeltas.isArray()) {
                for (JsonNode toolCallDelta : toolCallDeltas) {
                    int index = toolCallDelta.path("index").asInt(0);
                    ToolCallAccumulator acc = toolCalls.computeIfAbsent(index, i -> new ToolCallAccumulator());
                    String id = toolCallDelta.path("id").asText(null);
                    if (id != null) {
                        acc.id = id;
                    }
                    JsonNode function = toolCallDelta.path("function");
                    String name = function.path("name").asText(null);
                    if (name != null) {
                        acc.name = name;
                    }
                    String argsFragment = function.path("arguments").asText(null);
                    if (argsFragment != null) {
                        acc.arguments.append(argsFragment);
                    }
                }
            }
        }

        // A tool_calls delta only becomes a usable toolUse block once it has a non-blank id,
        // a non-blank name, and arguments that actually form a JSON object - a partial or
        // truncated fragment (e.g. an id with no name, or unclosed argument JSON) is dropped
        // instead of being surfaced as a broken tool_use block with empty identity fields.
        List<ToolCallAccumulator> validToolCalls = new ArrayList<>();
        for (ToolCallAccumulator acc : toolCalls.values()) {
            if (isUsableToolCall(mapper, acc)) {
                validToolCalls.add(acc);
            } else {
                LOG.warnv("Dropping incomplete or invalid tool_calls fragment from proxy backend: id={0}, name={1}",
                        acc.id, acc.name);
            }
        }

        boolean hasContent = text.length() > 0 || !validToolCalls.isEmpty();
        boolean streamCompleted = sawFinishReason || sawDone;
        if (!hasContent || !streamCompleted) {
            // Either no usable completion content was ever produced (empty/garbled body,
            // role/usage/finish_reason-only records, a data:-prefixed error object, or only
            // invalid tool_calls fragments), or the stream ended without a finish_reason or a
            // "[DONE]" terminator at all - a truncated generation (e.g. the connection dropped
            // mid-stream). Neither case is a real completion: surface it as a backend failure
            // rather than fabricating a successful message out of whatever partial data arrived.
            throw new AwsException("ModelErrorException",
                    "Proxy backend's ConverseStream response did not complete ("
                            + parsedChunks + " SSE chunk(s) parsed, streamCompleted=" + streamCompleted
                            + "): " + truncate(sseBody, 512), 424);
        }

        if (text.length() > 0) {
            events.add(new BedrockStreamEncoder.Event("contentBlockStop",
                    mapper.createObjectNode().put("contentBlockIndex", 0)));
        }

        boolean hasToolCalls = !validToolCalls.isEmpty();
        int blockIndex = text.length() > 0 ? 1 : 0;
        for (ToolCallAccumulator acc : validToolCalls) {
            ObjectNode startEvent = mapper.createObjectNode();
            startEvent.put("contentBlockIndex", blockIndex);
            ObjectNode toolUseStart = startEvent.putObject("start").putObject("toolUse");
            toolUseStart.put("toolUseId", acc.id);
            toolUseStart.put("name", acc.name);
            events.add(new BedrockStreamEncoder.Event("contentBlockStart", startEvent));

            ObjectNode deltaEvent = mapper.createObjectNode();
            deltaEvent.put("contentBlockIndex", blockIndex);
            deltaEvent.putObject("delta").putObject("toolUse").put("input", acc.arguments.toString());
            events.add(new BedrockStreamEncoder.Event("contentBlockDelta", deltaEvent));

            events.add(new BedrockStreamEncoder.Event("contentBlockStop",
                    mapper.createObjectNode().put("contentBlockIndex", blockIndex)));
            blockIndex++;
        }

        events.add(new BedrockStreamEncoder.Event("messageStop",
                mapper.createObjectNode().put("stopReason", hasToolCalls ? "tool_use" : mapFinishReason(finishReason))));

        ObjectNode metadata = mapper.createObjectNode();
        metadata.putObject("usage")
                .put("inputTokens", promptTokens)
                .put("outputTokens", completionTokens)
                .put("totalTokens", totalTokens);
        metadata.putObject("metrics").put("latencyMs", latencyMs);
        events.add(new BedrockStreamEncoder.Event("metadata", metadata));

        return BedrockStreamEncoder.encode(mapper, events);
    }

    private static boolean isUsableToolCall(ObjectMapper mapper, ToolCallAccumulator acc) {
        if (acc.id == null || acc.id.isBlank() || acc.name == null || acc.name.isBlank()) {
            return false;
        }
        try {
            return mapper.readTree(acc.arguments.toString()).isObject();
        } catch (Exception e) {
            return false;
        }
    }

    private static final class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private static String extractMessageText(JsonNode message) {
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return content.asText("");
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String text = part.path("text").asText(null);
                if (text != null) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static ObjectNode parseToolArguments(ObjectMapper mapper, String argumentsJson) {
        try {
            JsonNode parsed = mapper.readTree(argumentsJson);
            if (parsed.isObject()) {
                return (ObjectNode) parsed;
            }
        } catch (Exception e) {
            LOG.warnv("Failed to parse tool_call arguments as JSON: {0}", e.getMessage());
        }
        return mapper.createObjectNode();
    }

    private static String mapFinishReason(String finishReason) {
        return "length".equals(finishReason) ? "max_tokens" : "end_turn";
    }

    private static String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) + "…" : value;
    }
}

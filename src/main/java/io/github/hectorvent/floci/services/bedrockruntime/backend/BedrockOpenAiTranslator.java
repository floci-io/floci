package io.github.hectorvent.floci.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

/**
 * Translates between the Bedrock Runtime Converse wire shape and the OpenAI
 * Chat Completions wire shape used by Ollama, OpenRouter, LiteLLM, vLLM, etc.
 */
final class BedrockOpenAiTranslator {

    private static final Logger LOG = Logger.getLogger(BedrockOpenAiTranslator.class);

    private BedrockOpenAiTranslator() {
    }

    static ObjectNode toOpenAiRequest(ObjectMapper mapper, ObjectNode bedrockRequest, String resolvedModel) {
        ObjectNode openAi = mapper.createObjectNode();
        openAi.put("model", resolvedModel);
        openAi.put("stream", false);
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
}

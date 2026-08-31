package io.github.hectorvent.floci.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Forwards Bedrock Converse and InvokeModel requests to any OpenAI-compatible {@code /chat/completions}
 * endpoint (Ollama, OpenRouter, LiteLLM, vLLM, ...).
 */
@ApplicationScoped
public class ProxyBackend implements BedrockBackend {

    private static final Logger LOG = Logger.getLogger(ProxyBackend.class);

    private final ObjectMapper objectMapper;
    private final EmulatorConfig config;

    // Config is immutable for the process's lifetime, so the mapping string is parsed
    // once here rather than on every Converse/Invoke request.
    private final Map<String, String> modelMapping;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Inject
    public ProxyBackend(ObjectMapper objectMapper, EmulatorConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;
        this.modelMapping = parseModelMapping(config.services().bedrockRuntime().proxy().modelMapping().orElse(""));
    }

    @Override
    public ObjectNode converse(String modelId, ObjectNode bedrockRequest) {
        EmulatorConfig.BedrockProxyConfig proxyConfig = config.services().bedrockRuntime().proxy();
        String resolvedModel = resolveModel(modelId, proxyConfig);
        ObjectNode openAiRequest = BedrockOpenAiTranslator.toOpenAiRequest(objectMapper, bedrockRequest, resolvedModel);

        CallResult result = callOpenAiChatCompletions(modelId, openAiRequest, proxyConfig);
        return BedrockOpenAiTranslator.toBedrockResponse(objectMapper, result.responseJson, result.latencyMs);
    }

    @Override
    public byte[] invokeModel(String modelId, byte[] body) {
        EmulatorConfig.BedrockProxyConfig proxyConfig = config.services().bedrockRuntime().proxy();
        String resolvedModel = resolveModel(modelId, proxyConfig);

        JsonNode inputJson;
        try {
            inputJson = objectMapper.readTree(body != null && body.length > 0 ? body : new byte[]{'{', '}'});
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Malformed JSON body for InvokeModel: " + e.getMessage(), 400);
        }

        ObjectNode openAiRequest = buildOpenAiRequestFromInvokeModel(inputJson, resolvedModel);
        CallResult result = callOpenAiChatCompletions(modelId, openAiRequest, proxyConfig);

        ObjectNode bedrockInvokeResponse = formatBedrockInvokeResponse(modelId, inputJson, result.responseJson);
        try {
            return objectMapper.writeValueAsBytes(bedrockInvokeResponse);
        } catch (Exception e) {
            throw new AwsException("InternalServerException", "Failed to serialize InvokeModel response: " + e.getMessage(), 500);
        }
    }

    private ObjectNode buildOpenAiRequestFromInvokeModel(JsonNode inputJson, String resolvedModel) {
        if (inputJson.has("messages") && inputJson.path("messages").isArray()) {
            if (inputJson.isObject()) {
                return BedrockOpenAiTranslator.toOpenAiRequest(objectMapper, (ObjectNode) inputJson, resolvedModel);
            }
        }

        ObjectNode openAi = objectMapper.createObjectNode();
        openAi.put("model", resolvedModel);
        openAi.put("stream", false);
        ArrayNode openAiMessages = openAi.putArray("messages");

        if (inputJson.hasNonNull("system")) {
            JsonNode sys = inputJson.get("system");
            if (sys.isTextual()) {
                openAiMessages.addObject().put("role", "system").put("content", sys.asText());
            } else if (sys.isArray()) {
                for (JsonNode b : sys) {
                    String t = b.isTextual() ? b.asText() : b.path("text").asText(null);
                    if (t != null) {
                        openAiMessages.addObject().put("role", "system").put("content", t);
                    }
                }
            }
        }

        if (inputJson.hasNonNull("inputText")) {
            openAiMessages.addObject().put("role", "user").put("content", inputJson.get("inputText").asText());
        } else if (inputJson.hasNonNull("prompt")) {
            String prompt = inputJson.get("prompt").asText();
            openAiMessages.addObject().put("role", "user").put("content", prompt);
        } else if (inputJson.hasNonNull("messages") && inputJson.get("messages").isArray()) {
            for (JsonNode msg : inputJson.get("messages")) {
                String role = msg.path("role").asText("user");
                JsonNode content = msg.path("content");
                if (content.isTextual()) {
                    openAiMessages.addObject().put("role", role).put("content", content.asText());
                } else if (content.isArray()) {
                    ObjectNode openAiMsg = openAiMessages.addObject();
                    openAiMsg.put("role", role);
                    ArrayNode contentArray = openAiMsg.putArray("content");
                    for (JsonNode part : content) {
                        if (part.hasNonNull("text")) {
                            contentArray.addObject().put("type", "text").put("text", part.path("text").asText());
                        } else if (part.has("image") || "image".equalsIgnoreCase(part.path("type").asText())) {
                            JsonNode imgNode = part.has("image") ? part.path("image") : part;
                            String imgUrl = extractImageUrl(imgNode);
                            if (!imgUrl.isBlank()) {
                                ObjectNode partObj = contentArray.addObject();
                                partObj.put("type", "image_url");
                                partObj.putObject("image_url").put("url", imgUrl);
                            }
                        }
                    }
                }
            }
        }

        if (inputJson.hasNonNull("textGenerationConfig")) {
            JsonNode cfg = inputJson.get("textGenerationConfig");
            if (cfg.hasNonNull("maxTokenCount")) {
                openAi.put("max_tokens", cfg.get("maxTokenCount").asInt());
            }
            if (cfg.hasNonNull("temperature")) {
                openAi.put("temperature", cfg.get("temperature").asDouble());
            }
            if (cfg.hasNonNull("topP")) {
                openAi.put("top_p", cfg.get("topP").asDouble());
            }
            if (cfg.hasNonNull("stopSequences") && cfg.get("stopSequences").isArray()) {
                openAi.set("stop", cfg.get("stopSequences").deepCopy());
            }
        }

        if (inputJson.hasNonNull("max_tokens")) {
            openAi.put("max_tokens", inputJson.get("max_tokens").asInt());
        } else if (inputJson.hasNonNull("max_tokens_to_sample")) {
            openAi.put("max_tokens", inputJson.get("max_tokens_to_sample").asInt());
        } else if (inputJson.hasNonNull("max_gen_len")) {
            openAi.put("max_tokens", inputJson.get("max_gen_len").asInt());
        }

        if (inputJson.hasNonNull("temperature")) {
            openAi.put("temperature", inputJson.get("temperature").asDouble());
        }
        if (inputJson.hasNonNull("top_p")) {
            openAi.put("top_p", inputJson.get("top_p").asDouble());
        } else if (inputJson.hasNonNull("topP")) {
            openAi.put("top_p", inputJson.get("topP").asDouble());
        }

        if (inputJson.hasNonNull("stop_sequences") && inputJson.get("stop_sequences").isArray()) {
            openAi.set("stop", inputJson.get("stop_sequences").deepCopy());
        } else if (inputJson.hasNonNull("stop") && inputJson.get("stop").isArray()) {
            openAi.set("stop", inputJson.get("stop").deepCopy());
        }

        return openAi;
    }

    private static String extractImageUrl(JsonNode imageNode) {
        if (imageNode.has("source")) {
            JsonNode source = imageNode.path("source");
            if (source.has("bytes")) {
                String format = imageNode.path("format").asText("png");
                String bytes = source.path("bytes").asText();
                return "data:image/" + format + ";base64," + bytes;
            }
            if (source.has("data")) {
                String mediaType = source.path("media_type").asText("image/png");
                String data = source.path("data").asText();
                return "data:" + mediaType + ";base64," + data;
            }
        }
        if (imageNode.has("image_url")) {
            return imageNode.path("image_url").path("url").asText();
        }
        return "";
    }

    private ObjectNode formatBedrockInvokeResponse(String modelId, JsonNode inputJson, JsonNode openAiResponse) {
        JsonNode choice = openAiResponse.path("choices").path(0);
        JsonNode message = choice.path("message");
        String contentText = BedrockOpenAiTranslator.extractMessageText(message);

        JsonNode usage = openAiResponse.path("usage");
        int promptTokens = usage.path("prompt_tokens").asInt(10);
        int completionTokens = usage.path("completion_tokens").asInt(12);
        int totalTokens = usage.path("total_tokens").asInt(promptTokens + completionTokens);

        String lowerModel = modelId == null ? "" : modelId.toLowerCase();
        ObjectNode root = objectMapper.createObjectNode();

        if (lowerModel.startsWith("anthropic.") || lowerModel.contains(".anthropic.")
                || inputJson.hasNonNull("anthropic_version")) {
            if (inputJson.hasNonNull("max_tokens_to_sample") && !inputJson.hasNonNull("messages")) {
                root.put("completion", contentText);
                root.put("stop_reason", "stop_sequence");
                root.putNull("stop");
            } else {
                root.put("id", "msg_" + UUID.randomUUID().toString().replace("-", ""));
                root.put("type", "message");
                root.put("role", "assistant");
                root.put("model", modelId);
                ArrayNode content = root.putArray("content");
                ObjectNode textBlock = content.addObject();
                textBlock.put("type", "text");
                textBlock.put("text", contentText);
                root.put("stop_reason", "end_turn");
                root.putNull("stop_sequence");
                ObjectNode usageOut = root.putObject("usage");
                usageOut.put("input_tokens", promptTokens);
                usageOut.put("output_tokens", completionTokens);
            }
            return root;
        }

        if (lowerModel.startsWith("amazon.titan") || inputJson.hasNonNull("inputText")) {
            root.put("inputTextTokenCount", promptTokens);
            ArrayNode results = root.putArray("results");
            ObjectNode res = results.addObject();
            res.put("tokenCount", completionTokens);
            res.put("outputText", contentText);
            res.put("completionReason", "FINISH");
            return root;
        }

        if (lowerModel.startsWith("meta.llama") || inputJson.hasNonNull("max_gen_len")) {
            root.put("generation", contentText);
            root.put("prompt_token_count", promptTokens);
            root.put("generation_token_count", completionTokens);
            root.put("stop_reason", "stop");
            return root;
        }

        if (lowerModel.startsWith("cohere.")) {
            ArrayNode generations = root.putArray("generations");
            ObjectNode gen = generations.addObject();
            gen.put("text", contentText);
            gen.put("finish_reason", "COMPLETE");
            return root;
        }

        // Generic fallback with outputs / outputText / results
        root.put("outputText", contentText);
        root.put("generation", contentText);
        ArrayNode outputs = root.putArray("outputs");
        outputs.addObject().put("text", contentText);
        ArrayNode results = root.putArray("results");
        ObjectNode res = results.addObject();
        res.put("tokenCount", completionTokens);
        res.put("outputText", contentText);
        res.put("completionReason", "FINISH");
        ObjectNode usageOut = root.putObject("usage");
        usageOut.put("inputTokens", promptTokens);
        usageOut.put("outputTokens", completionTokens);
        usageOut.put("totalTokens", totalTokens);

        return root;
    }

    private CallResult callOpenAiChatCompletions(String modelId, ObjectNode openAiRequest, EmulatorConfig.BedrockProxyConfig proxyConfig) {
        String baseUrl = proxyConfig.url()
                .filter(url -> !url.isBlank())
                .orElseThrow(() -> new AwsException("ValidationException",
                        "floci.services.bedrock-runtime.proxy.url is required when backend=proxy.", 400));

        URI uri;
        HttpRequest.Builder builder;
        try {
            uri = URI.create(stripTrailingSlash(baseUrl) + "/chat/completions");
            builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(proxyConfig.requestTimeoutSeconds()))
                    .header("Content-Type", "application/json");
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException",
                    "floci.services.bedrock-runtime.proxy.url is not a valid URL: " + e.getMessage(), 400);
        }
        proxyConfig.apiKey()
                .filter(key -> !key.isBlank())
                .ifPresent(key -> builder.header("Authorization", "Bearer " + key));

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(openAiRequest);
        } catch (Exception e) {
            throw new AwsException("InternalServerException", "Failed to serialize proxy request: " + e.getMessage(), 500);
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(requestBody));

        long start = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            LOG.warnv("Bedrock proxy backend timed out: modelId={0}, url={1}, error={2}", modelId, uri, e.getMessage());
            throw new AwsException("ModelTimeoutException", "Proxy backend timed out: " + e.getMessage(), 408);
        } catch (Exception e) {
            LOG.warnv("Bedrock proxy backend call failed: modelId={0}, url={1}, error={2}", modelId, uri, e.getMessage());
            throw new AwsException("ModelErrorException", "Failed to reach proxy backend: " + e.getMessage(), 424);
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        if (response.statusCode() >= 300) {
            LOG.warnv("Bedrock proxy backend returned HTTP {0}: {1}", response.statusCode(), response.body());
            throw new AwsException("ModelErrorException",
                    "Proxy backend returned HTTP " + response.statusCode() + ": " + truncate(response.body(), 512),
                    424);
        }

        JsonNode openAiResponse;
        try {
            openAiResponse = objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AwsException("ModelErrorException", "Proxy backend returned malformed JSON: " + e.getMessage(), 424);
        }

        return new CallResult(openAiResponse, latencyMs);
    }

    private record CallResult(JsonNode responseJson, long latencyMs) {}

    String resolveModel(String bedrockModelId, EmulatorConfig.BedrockProxyConfig proxyConfig) {
        String mapped = modelMapping.get(bedrockModelId);
        if (mapped != null) {
            return mapped;
        }
        if (proxyConfig.passthrough()) {
            return bedrockModelId;
        }
        if (proxyConfig.defaultModel().isPresent()) {
            return proxyConfig.defaultModel().get();
        }
        throw new AwsException("ValidationException",
                "No model mapping found for: " + bedrockModelId
                        + ". Set FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_MODEL_MAPPING or "
                        + "FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_DEFAULT_MODEL", 400);
    }

    static Map<String, String> parseModelMapping(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq == trimmed.length() - 1) {
                LOG.warnv("Ignoring malformed bedrock-runtime proxy model-mapping entry: {0}", trimmed);
                continue;
            }
            result.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return result;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) + "…" : value;
    }
}

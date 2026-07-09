package io.github.hectorvent.floci.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forwards Bedrock Converse requests to any OpenAI-compatible {@code /chat/completions}
 * endpoint (Ollama, OpenRouter, LiteLLM, vLLM, ...). InvokeModel is not yet supported and
 * falls back to the stub response.
 */
@ApplicationScoped
public class ProxyBackend implements BedrockBackend {

    private static final Logger LOG = Logger.getLogger(ProxyBackend.class);

    private final ObjectMapper objectMapper;
    private final EmulatorConfig config;
    private final StubBackend stubBackend;

    // Config is immutable for the process's lifetime, so the mapping string is parsed
    // once here rather than on every Converse request.
    private final Map<String, String> modelMapping;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Inject
    public ProxyBackend(ObjectMapper objectMapper, EmulatorConfig config, StubBackend stubBackend) {
        this.objectMapper = objectMapper;
        this.config = config;
        this.stubBackend = stubBackend;
        this.modelMapping = parseModelMapping(config.services().bedrockRuntime().proxy().modelMapping().orElse(""));
    }

    @Override
    public ObjectNode converse(String modelId, ObjectNode bedrockRequest) {
        EmulatorConfig.BedrockProxyConfig proxyConfig = config.services().bedrockRuntime().proxy();
        String resolvedModel = resolveModel(modelId, proxyConfig);
        String baseUrl = proxyConfig.url()
                .filter(url -> !url.isBlank())
                .orElseThrow(() -> new AwsException("ValidationException",
                        "floci.services.bedrock-runtime.proxy.url is required when backend=proxy.", 400));

        ObjectNode openAiRequest = BedrockOpenAiTranslator.toOpenAiRequest(objectMapper, bedrockRequest, resolvedModel);

        URI uri;
        HttpRequest.Builder builder;
        try {
            uri = URI.create(stripTrailingSlash(baseUrl) + "/chat/completions");
            builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(60))
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
            throw new AwsException("InternalFailure", "Failed to serialize proxy request: " + e.getMessage(), 500);
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(requestBody));

        long start = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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

        return BedrockOpenAiTranslator.toBedrockResponse(objectMapper, openAiResponse, latencyMs);
    }

    @Override
    public byte[] invokeModel(String modelId, byte[] body) {
        LOG.warnv("Bedrock proxy backend does not support InvokeModel yet; returning stub response for modelId={0}",
                modelId);
        return stubBackend.invokeModel(modelId, body);
    }

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

package io.github.hectorvent.floci.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Forwards Bedrock Converse requests to any OpenAI-compatible {@code /chat/completions}
 * endpoint (Ollama, OpenRouter, LiteLLM, vLLM, ...). InvokeModel is not yet supported and
 * fails fast rather than returning a fabricated response.
 */
@ApplicationScoped
public class ProxyBackend implements BedrockBackend {

    private static final Logger LOG = Logger.getLogger(ProxyBackend.class);

    private final ObjectMapper objectMapper;
    private final EmulatorConfig config;

    // Config is immutable for the process's lifetime, so the mapping string is parsed
    // once here rather than on every Converse request.
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
        ObjectNode openAiRequest = BedrockOpenAiTranslator.toOpenAiRequest(objectMapper, bedrockRequest, resolvedModel, false);
        HttpRequest request = buildHttpRequest(proxyConfig, openAiRequest);

        long start = System.nanoTime();
        HttpResponse<String> response = invoke(modelId, request, HttpResponse.BodyHandlers.ofString());
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        if (response.statusCode() >= 300) {
            throw failedRequest(response.statusCode(), response.body());
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
        throw new AwsException("ValidationException",
                "InvokeModel is not supported by the bedrock-runtime proxy backend; use Converse.", 400);
    }

    @Override
    public Consumer<OutputStream> converseStream(String modelId, ObjectNode bedrockRequest) {
        EmulatorConfig.BedrockProxyConfig proxyConfig = config.services().bedrockRuntime().proxy();
        String resolvedModel = resolveModel(modelId, proxyConfig);
        ObjectNode openAiRequest = BedrockOpenAiTranslator.toOpenAiRequest(objectMapper, bedrockRequest, resolvedModel, true);

        // Everything that can fail with a proper HTTP status - resolving the model, opening the
        // connection, checking the upstream status code - happens here, before any bytes of our
        // own response are written. Once the returned Consumer is invoked, JAX-RS has
        // already committed a 200, so failures discovered while consuming the body stream can
        // only be reported as an in-band Bedrock stream exception event (see
        // BedrockOpenAiTranslator.streamBedrockEvents).
        HttpRequest request = buildHttpRequest(proxyConfig, openAiRequest);
        long startNanos = System.nanoTime();
        HttpResponse<Stream<String>> response = invoke(modelId, request, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() >= 300) {
            String errorBody;
            try (Stream<String> body = response.body()) {
                errorBody = body.limit(50).collect(Collectors.joining("\n"));
            }
            throw failedRequest(response.statusCode(), errorBody);
        }

        return output -> BedrockOpenAiTranslator.streamBedrockEvents(objectMapper, response.body(), output, startNanos);
    }

    private static AwsException failedRequest(int response, String errorBody) {
        LOG.warnv("Bedrock proxy backend returned HTTP {0}: {1}", response, errorBody);
        return new AwsException("ModelErrorException",
                "Proxy backend returned HTTP %d: %s".formatted(response, truncate(errorBody, 512)), 424);
    }

    private <T> HttpResponse<T> invoke(String modelId, HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        try {
            return httpClient.send(request, bodyHandler);
        } catch (HttpTimeoutException e) {
            LOG.warnv("Bedrock proxy backend timed out: modelId={0}, url={1}, error={2}",
                    modelId, request.uri(), e.getMessage());
            throw new AwsException("ModelTimeoutException", "Proxy backend timed out: " + e.getMessage(), 408);
        } catch (Exception e) {
            LOG.warnv("Bedrock proxy backend call failed: modelId={0}, url={1}, error={2}",
                    modelId, request.uri(), e.getMessage());
            throw new AwsException("ModelErrorException", "Failed to reach proxy backend: " + e.getMessage(), 424);
        }
    }

    private HttpRequest buildHttpRequest(EmulatorConfig.BedrockProxyConfig proxyConfig, ObjectNode openAiRequest) {
        String baseUrl = proxyConfig.url()
                .filter(url -> !url.isBlank())
                .orElseThrow(() -> new AwsException("ValidationException",
                        "floci.services.bedrock-runtime.proxy.url is required when backend=proxy.", 400));

        byte[] requestBody;
        try {
            requestBody = objectMapper.writeValueAsBytes(openAiRequest);
        } catch (Exception e) {
            throw new AwsException("InternalServerException", "Failed to serialize proxy request: " + e.getMessage(), 500);
        }

        HttpRequest.Builder builder;
        try {
            URI uri = URI.create(stripTrailingSlash(baseUrl) + "/chat/completions");
            builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .timeout(Duration.ofSeconds(proxyConfig.requestTimeoutSeconds()))
                    .header("Content-Type", "application/json");
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException",
                    "floci.services.bedrock-runtime.proxy.url is not a valid URL: " + e.getMessage(), 400);
        }
        proxyConfig.apiKey()
                .filter(key -> !key.isBlank())
                .ifPresent(key -> builder.header("Authorization", "Bearer " + key));

        return builder.build();
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

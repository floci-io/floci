package io.github.hectorvent.floci.services.floci.appsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP client for the floci-app-sync sidecar (issue #2917). Wraps its three endpoints:
 * <ul>
 *   <li>{@code POST /schemas/{apiId}} — compile+register an SDL, used by {@code StartSchemaCreation}</li>
 *   <li>{@code DELETE /schemas/{apiId}} — evict a compiled schema, used by {@code DeleteGraphqlApi}</li>
 *   <li>{@code POST /schemas/{apiId}/execute} — run a query, used by the data-plane controller</li>
 * </ul>
 */
@ApplicationScoped
public class FlociAppSyncClient {

    private final FlociAppSyncManager manager;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Inject
    public FlociAppSyncClient(FlociAppSyncManager manager, ObjectMapper mapper) {
        this.manager = manager;
        this.mapper = mapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    public boolean isAvailable() {
        return manager.isAvailable();
    }

    /**
     * Compiles and registers {@code sdl} under {@code apiId}. Throws the same
     * {@link AwsException} shape {@code AppSyncSchemaParser} used to throw in-process, so
     * callers (namely {@code SchemaCreationWorker}) don't need to change their catch logic.
     */
    public void compileSchema(String apiId, String sdl) {
        String url = manager.ensureReady();
        Map<String, Object> body = Map.of("sdl", sdl);
        HttpResponse<String> response = post(url + "/schemas/" + apiId, body);
        if (response.statusCode() == 200) {
            return;
        }
        if (response.statusCode() == 400) {
            Map<String, Object> parsed = readBody(response.body());
            String message = String.valueOf(parsed.getOrDefault("message", "Invalid schema"));
            @SuppressWarnings("unchecked")
            Map<String, Object> extendedData = (Map<String, Object>) parsed.get("extendedData");
            throw new AwsException("BadRequestException", message, 400, extendedData);
        }
        throw new AwsException("InternalFailure",
                "floci-app-sync schema compile returned HTTP " + response.statusCode(), 500);
    }

    /** Evicts a compiled schema. A no-op on the sidecar side if nothing was ever compiled. */
    public void deleteSchema(String apiId) {
        String url = manager.ensureReady();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/schemas/" + apiId))
                    .DELETE()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new RuntimeException("Failed to call floci-app-sync delete-schema: " + e.getMessage(), e);
        }
    }

    /**
     * Runs a query against the compiled schema for {@code apiId}. Returns the raw HTTP status
     * and JSON body from the sidecar unchanged — the caller (the data-plane controller) maps
     * status/errorType exactly the same way it did when the engine ran in-process.
     */
    public ExecuteResult execute(String apiId, String query, Map<String, Object> variables,
                                 String operationName, Object authContext) {
        String url = manager.ensureReady();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("variables", variables);
        body.put("operationName", operationName);
        body.put("authContext", authContext);

        HttpResponse<String> response = post(url + "/schemas/" + apiId + "/execute", body);
        return new ExecuteResult(response.statusCode(), readBody(response.body()));
    }

    public record ExecuteResult(int status, Map<String, Object> body) {
    }

    private HttpResponse<String> post(String url, Map<String, Object> body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to call floci-app-sync: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = mapper.readTree(responseBody);
            return mapper.convertValue(root, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}

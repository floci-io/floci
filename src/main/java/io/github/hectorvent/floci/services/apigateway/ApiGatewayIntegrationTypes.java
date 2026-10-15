package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Integration types generated from Botocore's public API Gateway service model. */
final class ApiGatewayIntegrationTypes {

    private static final String RESOURCE_NAME = "aws/apigateway-integration-types.json";
    private static final Map<String, String> TYPES = load();

    private ApiGatewayIntegrationTypes() {
    }

    static String canonical(String value) {
        if (value.chars().anyMatch(character -> character > 0x7f)) {
            return null;
        }
        return TYPES.get(value.toLowerCase(Locale.ROOT));
    }

    private static Map<String, String> load() {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (input == null) {
                throw new IllegalStateException("Missing API Gateway integration types: " + RESOURCE_NAME);
            }
            JsonNode values = new ObjectMapper().readTree(input).path("integrationTypes");
            if (!values.isArray() || values.isEmpty()) {
                throw new IllegalStateException(RESOURCE_NAME + " carries no integrationTypes");
            }
            Map<String, String> types = new HashMap<>();
            for (JsonNode value : values) {
                if (!value.isTextual() || value.asText().isBlank()) {
                    throw new IllegalStateException(RESOURCE_NAME + " carries an invalid integration type");
                }
                types.put(value.asText().toLowerCase(Locale.ROOT), value.asText());
            }
            return Map.copyOf(types);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read " + RESOURCE_NAME, error);
        }
    }
}

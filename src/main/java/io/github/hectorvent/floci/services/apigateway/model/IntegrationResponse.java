package io.github.hectorvent.floci.services.apigateway.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public record IntegrationResponse(
        String statusCode,
        String selectionPattern,
        Map<String, String> responseParameters,
        Map<String, String> responseTemplates,
        /** CONVERT_TO_BINARY, CONVERT_TO_TEXT, or null to pass the payload through unchanged. */
        String contentHandling
) {
    /** Backwards-compatible factory for responses that specify no content handling. */
    public IntegrationResponse(String statusCode, String selectionPattern,
                               Map<String, String> responseParameters,
                               Map<String, String> responseTemplates) {
        this(statusCode, selectionPattern, responseParameters, responseTemplates, null);
    }
}

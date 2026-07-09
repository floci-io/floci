package io.github.hectorvent.floci.services.apigateway.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public record MethodResponse(
        String statusCode,
        Map<String, Boolean> responseParameters
) {
}

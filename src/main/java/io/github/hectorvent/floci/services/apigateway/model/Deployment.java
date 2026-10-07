package io.github.hectorvent.floci.services.apigateway.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public record Deployment(
        String id,
        String description,
        long createdDate,
        String exportSnapshot
) {
    public Deployment(String id, String description, long createdDate) {
        this(id, description, createdDate, null);
    }
}

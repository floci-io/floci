package io.github.hectorvent.floci.services.controlcatalog.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record ControlDefinition(
        String globalIdentifier,
        List<String> aliases,
        String name,
        String description,
        String behavior,
        String severity,
        String scope,
        String implementationType,
        List<String> parameters) {
}

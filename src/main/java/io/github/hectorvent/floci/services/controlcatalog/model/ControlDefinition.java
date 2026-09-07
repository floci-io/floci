package io.github.hectorvent.floci.services.controlcatalog.model;

import java.util.List;

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

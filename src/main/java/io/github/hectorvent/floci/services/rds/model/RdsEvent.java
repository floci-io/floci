package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
public record RdsEvent(
        String id,
        String sourceIdentifier,
        String sourceType,
        String message,
        List<String> eventCategories,
        Instant date,
        String sourceArn) {
}

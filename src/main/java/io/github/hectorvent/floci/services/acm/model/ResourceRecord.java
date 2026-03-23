package io.github.hectorvent.floci.services.acm.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record ResourceRecord(
    String name,
    String type,
    String value
) {}

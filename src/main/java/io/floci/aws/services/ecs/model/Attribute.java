package io.floci.aws.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record Attribute(String name, String value, String targetType, String targetId) {}

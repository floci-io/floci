package io.floci.aws.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record Secret(String name, String valueFrom) {
}

package io.floci.aws.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record KeyValuePair(String name, String value) {
}
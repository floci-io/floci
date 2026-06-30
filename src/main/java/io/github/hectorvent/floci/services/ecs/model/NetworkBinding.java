package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record NetworkBinding(String bindIP, int containerPort, int hostPort, String protocol) {
}

package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record PortMapping(int containerPort, int hostPort, String protocol) {

    public PortMapping(int containerPort) {
        this(containerPort, 0, "tcp");
    }
}

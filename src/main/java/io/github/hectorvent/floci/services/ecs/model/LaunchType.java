package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum LaunchType {
    EC2, FARGATE, EXTERNAL
}
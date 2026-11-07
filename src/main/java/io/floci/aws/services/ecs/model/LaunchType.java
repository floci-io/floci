package io.floci.aws.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum LaunchType {
    EC2, FARGATE, EXTERNAL, MANAGED_INSTANCES
}
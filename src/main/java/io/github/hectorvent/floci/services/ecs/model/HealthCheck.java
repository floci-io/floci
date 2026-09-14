package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code healthCheck} of an ECS container definition:
 * {@code {"command": [...], "interval": ..., "timeout": ..., "retries": ..., "startPeriod": ...}}.
 *
 * <p>Modelled for RegisterTaskDefinition/DescribeTaskDefinition round-trip fidelity.
 */
@RegisterForReflection
public record HealthCheck(
        List<String> command,
        Integer interval,
        Integer timeout,
        Integer retries,
        Integer startPeriod
) {
}

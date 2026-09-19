package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A {@code dependsOn} entry of an ECS container definition:
 * {@code {"containerName": ..., "condition": "START"|"COMPLETE"|"SUCCESS"|"HEALTHY"}}.
 *
 * <p>The condition decides how far the named container must get before the dependent one starts.
 */
@RegisterForReflection
public record ContainerDependency(String containerName, String condition) {

    public static final String START = "START";
    public static final String COMPLETE = "COMPLETE";
    public static final String SUCCESS = "SUCCESS";
    public static final String HEALTHY = "HEALTHY";
}

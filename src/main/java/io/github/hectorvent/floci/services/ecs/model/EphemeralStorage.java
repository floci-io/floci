package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code ephemeralStorage} of a Fargate task definition or a RunTask override:
 * {@code {"sizeInGiB": 21}}.
 *
 * <p>Floci reports the requested size but does not cap the container's writable layer at it.
 */
@RegisterForReflection
public record EphemeralStorage(int sizeInGiB) {

    /** The storage a Fargate task gets without asking, and the floor for an explicit request. */
    public static final int DEFAULT_SIZE_IN_GIB = 20;
    public static final int MAX_SIZE_IN_GIB = 200;
}

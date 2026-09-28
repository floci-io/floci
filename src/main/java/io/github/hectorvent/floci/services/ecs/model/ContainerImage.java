package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One container image a service revision pins. {@code imageDigest} is the digest the image
 * resolved to; a revision Floci minted from a task definition alone has not pulled anything yet
 * and reports none.
 */
@RegisterForReflection
public record ContainerImage(String containerName, String image, String imageDigest) {
}

package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An {@code environmentFiles} entry of an ECS container definition:
 * {@code {"value": "<s3 object arn>", "type": "s3"}}.
 */
@RegisterForReflection
public record EnvironmentFile(String value, String type) {
}

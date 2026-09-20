package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * The {@code logConfiguration} of an ECS container definition:
 * {@code {"logDriver": "awslogs", "options": {...}, "secretOptions": [{"name": ..., "valueFrom": ...}]}}.
 *
 * <p>Stored and returned for RegisterTaskDefinition/DescribeTaskDefinition round-trip. At launch,
 * {@code awsfirelens} is routed through the task's FireLens container. Other drivers
 * (including {@code awslogs}) stay with the Docker container and Floci's CloudWatch log streamer.
 */
@RegisterForReflection
public record LogConfiguration(String logDriver, Map<String, String> options, List<Secret> secretOptions) {
}

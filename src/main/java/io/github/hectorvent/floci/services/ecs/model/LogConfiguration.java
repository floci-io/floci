package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * The {@code logConfiguration} of an ECS container definition:
 * {@code {"logDriver": "awslogs", "options": {"awslogs-group": ..., "awslogs-region": ...,
 * "awslogs-stream-prefix": ...}, "secretOptions": [{"name": ..., "valueFrom": ...}]}}.
 *
 * <p>Floci does not ship logs anywhere; this is modelled purely for
 * RegisterTaskDefinition/DescribeTaskDefinition round-trip fidelity so a real log
 * driver configuration (most commonly {@code awslogs}) does not disappear on read
 * and force a spurious replace plan.
 */
@RegisterForReflection
public record LogConfiguration(String logDriver, Map<String, String> options, List<Secret> secretOptions) {
}

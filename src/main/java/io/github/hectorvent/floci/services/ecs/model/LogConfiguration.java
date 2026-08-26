package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * The {@code logConfiguration} of an ECS container definition:
 * {@code {"logDriver": "awslogs", "options": {...}, "secretOptions": [{"name": ..., "valueFrom": ...}]}}.
 *
 * <p>Modelled for RegisterTaskDefinition/DescribeTaskDefinition round-trip fidelity. Floci does not
 * route container output to the configured driver — a local task's logs stay with its Docker
 * container — so the {@code awslogs} options are stored and returned rather than acted upon.
 */
@RegisterForReflection
public record LogConfiguration(String logDriver, Map<String, String> options, List<Secret> secretOptions) {
}

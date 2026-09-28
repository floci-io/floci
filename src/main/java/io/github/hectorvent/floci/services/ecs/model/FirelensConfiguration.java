package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * The {@code firelensConfiguration} of an ECS container definition:
 * {@code {"type": "fluentbit"|"fluentd", "options": {...}}}.
 *
 * <p>Marks a container as the task's log router and, when {@code enable-ecs-log-metadata} is set,
 * has Floci append ECS metadata fields to routed records. {@code fluentbit} and {@code fluentd}
 * routers are both acted on at launch.
 */
@RegisterForReflection
public record FirelensConfiguration(String type, Map<String, String> options) {
}

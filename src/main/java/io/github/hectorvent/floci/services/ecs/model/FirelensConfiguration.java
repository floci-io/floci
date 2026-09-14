package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * The {@code firelensConfiguration} of an ECS container definition:
 * {@code {"type": "fluentbit"|"fluentd", "options": {...}}}.
 *
 * <p>Marks a container as the task's log router and, when {@code enable-ecs-log-metadata} is set,
 * has Floci append ECS metadata fields to routed records. Only {@code fluentbit} log routers are
 * acted on at launch; a {@code fluentd} router is stored and returned for round-trip fidelity but
 * treated like a regular container, matching the deprecation direction on AWS.
 */
@RegisterForReflection
public record FirelensConfiguration(String type, Map<String, String> options) {
}

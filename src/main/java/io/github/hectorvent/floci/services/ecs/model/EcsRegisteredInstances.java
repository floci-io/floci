package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The Cloud Map instance ECS registered for one task and the Cloud Map services it joined,
 * persisted so a restarted Floci, which knows no task, deregisters exactly these and never an
 * instance registered through the Cloud Map API.
 */
@RegisterForReflection
public record EcsRegisteredInstances(String region, String instanceId, List<String> cloudMapServiceIds) {
}

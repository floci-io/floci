package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * One entry of the event log {@code DescribeServices} reports on {@code services[].events}.
 * Tools tail it for the "has reached a steady state" message the scheduler emits once a
 * deployment converges.
 */
@RegisterForReflection
public record ServiceEvent(String id, Instant createdAt, String message) {
}

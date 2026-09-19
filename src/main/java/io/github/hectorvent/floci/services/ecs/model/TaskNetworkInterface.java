package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A {@code networkInterfaces} entry of a described container:
 * {@code {"attachmentId": ..., "privateIpv4Address": ...}}.
 *
 * <p>An {@code awsvpc} task reports the same interface on each of its containers, since they
 * share the task ENI.
 */
@RegisterForReflection
public record TaskNetworkInterface(String attachmentId, String privateIpv4Address, String ipv6Address) {
}

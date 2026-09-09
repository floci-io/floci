package io.github.hectorvent.floci.services.ram.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One entry of a RAM ListResources response: a resource ARN belonging to a share, with the
 * RAM resource type ({@code service:CamelCasedResource}, e.g. {@code ec2:TransitGateway}).
 */
@RegisterForReflection
public record SharedResource(String arn, String type, String resourceShareArn, String status) {
}

package io.floci.aws.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record Endpoint(String address, int port) {}

package io.floci.aws.services.memorydb.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record Endpoint(String address, int port) {}

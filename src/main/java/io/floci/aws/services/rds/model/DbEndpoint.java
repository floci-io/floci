package io.floci.aws.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record DbEndpoint(String address, int port) {}

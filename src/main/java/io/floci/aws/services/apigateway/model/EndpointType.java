package io.floci.aws.services.apigateway.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum EndpointType {
    REGIONAL,
    EDGE,
    PRIVATE
}

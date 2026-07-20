package io.github.hectorvent.floci.services.apigateway.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum EndpointType {
    REGIONAL,
    EDGE,
    PRIVATE
}

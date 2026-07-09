package io.github.hectorvent.floci.services.appsync.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum ResolverKind {
    UNIT,
    PIPELINE
}

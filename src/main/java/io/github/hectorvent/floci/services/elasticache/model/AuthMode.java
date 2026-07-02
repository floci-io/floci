package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum AuthMode {
    IAM, PASSWORD, NO_AUTH
}

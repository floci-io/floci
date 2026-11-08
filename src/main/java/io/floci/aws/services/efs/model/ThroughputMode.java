package io.floci.aws.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum ThroughputMode {
    bursting,
    provisioned,
    elastic
}

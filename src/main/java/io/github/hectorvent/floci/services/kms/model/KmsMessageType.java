package io.github.hectorvent.floci.services.kms.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum KmsMessageType {
    RAW, DIGEST
}

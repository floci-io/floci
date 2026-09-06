package io.github.hectorvent.floci.services.verifiedpermissions.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record EntityIdentifier(String entityType, String entityId) {}

package io.github.hectorvent.floci.services.eks.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AccessConfig(String authenticationMode, Boolean bootstrapClusterCreatorAdminPermissions) {}

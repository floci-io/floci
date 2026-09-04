package io.github.hectorvent.floci.services.identitystore.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record Group(String groupId, String identityStoreId, String displayName, String description) {}

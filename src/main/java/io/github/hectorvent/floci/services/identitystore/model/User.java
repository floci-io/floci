package io.github.hectorvent.floci.services.identitystore.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record User(String userId, String identityStoreId, String userName, String displayName) {}

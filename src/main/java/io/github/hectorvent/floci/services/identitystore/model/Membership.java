package io.github.hectorvent.floci.services.identitystore.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record Membership(String membershipId, String identityStoreId, String groupId, String userId) {}

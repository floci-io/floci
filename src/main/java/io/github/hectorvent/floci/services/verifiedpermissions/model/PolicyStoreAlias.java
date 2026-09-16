package io.github.hectorvent.floci.services.verifiedpermissions.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public record PolicyStoreAlias(
        String aliasName,
        String aliasArn,
        String policyStoreId,
        String state,
        Instant createdAt,
        Instant deleteAfter) {

    public PolicyStoreAlias pendingDeletion(Instant deleteAfter) {
        return new PolicyStoreAlias(aliasName, aliasArn, policyStoreId, "PendingDeletion", createdAt, deleteAfter);
    }
}

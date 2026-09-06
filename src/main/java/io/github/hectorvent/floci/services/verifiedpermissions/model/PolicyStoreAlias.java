package io.github.hectorvent.floci.services.verifiedpermissions.model;

import java.time.Instant;

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

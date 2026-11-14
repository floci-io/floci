package io.floci.aws.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record PermissionSetProvisioning(String permissionSetArn, String accountId, String status,
                                       String roleArn, String roleId) {
    public PermissionSetProvisioning(String permissionSetArn, String accountId, String status) {
        this(permissionSetArn, accountId, status, null, null);
    }
}

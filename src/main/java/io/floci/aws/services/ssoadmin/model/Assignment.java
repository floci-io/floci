package io.floci.aws.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record Assignment(String accountId, String permissionSetArn, String principalId, String principalType) {}

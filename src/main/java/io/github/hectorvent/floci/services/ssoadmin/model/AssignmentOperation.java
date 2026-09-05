package io.github.hectorvent.floci.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AssignmentOperation(String requestId, String status, String accountId, String permissionSetArn,
                                  String principalId, String principalType, String failureReason) {}

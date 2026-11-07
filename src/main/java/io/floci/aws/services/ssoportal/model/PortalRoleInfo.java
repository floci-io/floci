package io.floci.aws.services.ssoportal.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record PortalRoleInfo(String accountId, String roleName) {}

package io.github.hectorvent.floci.services.ssoportal.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record PortalRoleCredentials(
        String accessKeyId,
        long expiration,
        String secretAccessKey,
        String sessionToken) {}

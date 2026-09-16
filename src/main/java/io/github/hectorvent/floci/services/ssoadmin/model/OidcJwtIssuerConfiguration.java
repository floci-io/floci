package io.github.hectorvent.floci.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record OidcJwtIssuerConfiguration(
        String claimAttributePath,
        String identityStoreAttributePath,
        String issuerUrl,
        String jwksRetrievalOption) {
}

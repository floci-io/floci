package io.github.hectorvent.floci.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public record TrustedTokenIssuer(
        String trustedTokenIssuerArn,
        String instanceArn,
        String name,
        String trustedTokenIssuerType,
        OidcJwtIssuerConfiguration oidcJwtConfiguration,
        Map<String, String> tags) {
    public TrustedTokenIssuer {
        tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}

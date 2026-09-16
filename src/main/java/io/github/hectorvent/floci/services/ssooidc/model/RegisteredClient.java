package io.github.hectorvent.floci.services.ssooidc.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record RegisteredClient(
        String clientId,
        String clientSecret,
        long clientIdIssuedAt,
        long clientSecretExpiresAt,
        String clientName,
        String clientType,
        List<String> scopes,
        List<String> redirectUris,
        List<String> grantTypes,
        String issuerUrl,
        String entitledApplicationArn
) {
    public RegisteredClient {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
        grantTypes = grantTypes == null ? List.of() : List.copyOf(grantTypes);
    }
}

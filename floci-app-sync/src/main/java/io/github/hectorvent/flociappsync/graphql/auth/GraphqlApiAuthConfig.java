package io.github.hectorvent.flociappsync.graphql.auth;

import java.util.List;

/**
 * The slice of Floci's {@code GraphqlApi} model that field-level authorization actually
 * needs: which auth modes the API accepts. Deliberately not the full model (uris, tags,
 * xrayEnabled, etc.) — Floci sends this as part of every {@code /execute} call's auth
 * context, so it stays minimal on purpose.
 */
public record GraphqlApiAuthConfig(
        String apiId,
        AuthenticationType authenticationType,
        List<AuthenticationType> additionalAuthenticationTypes
) {
    public GraphqlApiAuthConfig {
        additionalAuthenticationTypes = additionalAuthenticationTypes == null
                ? List.of() : List.copyOf(additionalAuthenticationTypes);
    }

    /** Equivalent of Floci's {@code AuthMiddleware.hasAdditionalModes}. */
    public boolean hasAdditionalModes() {
        return !additionalAuthenticationTypes.isEmpty();
    }
}

package io.floci.aws.core.common;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;

/**
 * Resolves the service that {@link AwsQueryController} will dispatch a Query-protocol action to.
 * IAM enforcement uses the same result so a caller-chosen signing scope cannot select a different
 * policy namespace from the handler that actually runs.
 */
@ApplicationScoped
public class AwsQueryServiceResolver {

    private final ResolvedServiceCatalog catalog;

    @Inject
    public AwsQueryServiceResolver(ResolvedServiceCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * The service named by a Query-capable signing scope, else the one the action and the request's
     * declared {@code Version} lead to. Both callers read {@code Version} from the form body, as they
     * do the action, so they cannot disagree about which service a request is for.
     */
    public String resolve(String authorization, String action, String version) {
        ServiceDescriptor descriptor = SigV4CredentialScope.serviceName(authorization)
                .flatMap(catalog::byCredentialScope)
                .orElse(null);
        if (descriptor != null && descriptor.supportsProtocol(ServiceProtocol.QUERY)) {
            return descriptor.externalKey();
        }
        return AwsQueryController.inferServiceFromAction(action, version);
    }

    /** Returns the operation the Query controller will dispatch from a form request. */
    public static String action(MultivaluedMap<String, String> formParams) {
        return action(formParams.getFirst("Action"), formParams.getFirst("Operation"));
    }

    /** Applies the Query controller's legacy {@code Operation} fallback. */
    public static String action(String action, String operation) {
        return action != null ? action : operation;
    }
}

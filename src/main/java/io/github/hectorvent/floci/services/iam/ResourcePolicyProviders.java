package io.github.hectorvent.floci.services.iam;

import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects what the registered {@link ResourcePolicyProvider}s supply for one resource. Shared by the
 * request filter, for the resource a request names, and by the authorizer for calls one AWS service
 * makes to another's resource, so both see the same policies and the same owning account.
 */
public final class ResourcePolicyProviders {

    private ResourcePolicyProviders() {
    }

    /**
     * The resource-based policies every provider supplies for {@code resourceArn}, in provider order,
     * or an empty list when there are no providers or none applies.
     */
    public static List<ResourcePolicyProvider.ResourcePolicy> policiesFor(
            Instance<ResourcePolicyProvider> providers, String credentialScope, String resourceArn) {
        return policiesFor(providers, credentialScope, null, resourceArn);
    }

    /** The policies applying to an action, as determined by each resource-owning service. */
    public static List<ResourcePolicyProvider.ResourcePolicy> policiesFor(
            Instance<ResourcePolicyProvider> providers, String credentialScope, String action, String resourceArn) {
        if (providers == null || providers.isUnsatisfied()) {
            return List.of();
        }
        List<ResourcePolicyProvider.ResourcePolicy> policies = new ArrayList<>();
        for (ResourcePolicyProvider provider : providers) {
            List<ResourcePolicyProvider.ResourcePolicy> supplied =
                    action == null ? provider.getResourcePolicies(credentialScope, resourceArn)
                            : provider.getResourcePolicies(credentialScope, action, resourceArn);
            if (supplied != null && !supplied.isEmpty()) {
                policies.addAll(supplied);
            }
        }
        return policies;
    }
}

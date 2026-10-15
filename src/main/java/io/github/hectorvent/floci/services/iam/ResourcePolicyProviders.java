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
        if (providers == null || providers.isUnsatisfied()) {
            return List.of();
        }
        List<ResourcePolicyProvider.ResourcePolicy> policies = new ArrayList<>();
        for (ResourcePolicyProvider provider : providers) {
            List<ResourcePolicyProvider.ResourcePolicy> supplied =
                    provider.getResourcePolicies(credentialScope, resourceArn);
            if (supplied != null && !supplied.isEmpty()) {
                policies.addAll(supplied);
            }
        }
        return policies;
    }
}

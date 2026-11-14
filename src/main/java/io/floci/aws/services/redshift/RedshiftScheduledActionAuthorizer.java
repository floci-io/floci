package io.floci.aws.services.redshift;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestResources;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.iam.IamAuthorizedActions;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.iam.ServiceRoleAuthorizer.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Decides whether the IAM role of a scheduled action may pause or resume its cluster. The role has to
 * trust the Redshift scheduler principal, and its identity policies, boundary and SCPs have to allow
 * {@code redshift:PauseCluster} or {@code redshift:ResumeCluster} on the cluster. Only active with IAM
 * enforcement on, as for every other API call. The action and the cluster resource come from the same
 * generated tables a direct request to the operation is checked with.
 */
@ApplicationScoped
public class RedshiftScheduledActionAuthorizer {

    private static final String SCHEDULER_PRINCIPAL = ServicePrincipals.of("scheduler.redshift");

    private final ServiceRoleAuthorizer authorizer;
    private final RegionResolver regionResolver;

    @Inject
    public RedshiftScheduledActionAuthorizer(ServiceRoleAuthorizer authorizer, RegionResolver regionResolver) {
        this.authorizer = authorizer;
        this.regionResolver = regionResolver;
    }

    /**
     * Returns why {@code roleArn} may not run {@code operation}, {@code PauseCluster} or {@code ResumeCluster},
     * on the cluster {@code clusterIdentifier} of {@code region}, or nothing when it may.
     */
    public Optional<String> refusal(String roleArn, String accountId, String region, String operation,
                                    String clusterIdentifier) {
        String namedAs = "redshift:" + operation;
        String action = IamAuthorizedActions.actionFor("redshift", operation);
        ObjectNode request = JsonNodeFactory.instance.objectNode().put("ClusterIdentifier", clusterIdentifier);
        List<Permission> permissions = RequestResources.ofBody(namedAs, request,
                regionResolver.partitionForRegion(region), region, accountId, null).stream()
                .map(resource -> new Permission(action, resource))
                .toList();
        if (permissions.isEmpty()) {
            return Optional.of("The scheduled action's target names no cluster to run " + action + " on.");
        }
        return authorizer.refusal(roleArn, accountId, SCHEDULER_PRINCIPAL, null, permissions);
    }
}

package io.floci.aws.services.redshift;

import io.floci.aws.core.common.ServicePrincipals;
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
 * enforcement on, as for every other API call.
 */
@ApplicationScoped
public class RedshiftScheduledActionAuthorizer {

    private static final String SCHEDULER_PRINCIPAL = ServicePrincipals.of("scheduler.redshift");

    private final ServiceRoleAuthorizer authorizer;

    @Inject
    public RedshiftScheduledActionAuthorizer(ServiceRoleAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    /** Returns why {@code roleArn} may not run {@code action} on {@code clusterArn}, or nothing when it may. */
    public Optional<String> refusal(String roleArn, String accountId, String action, String clusterArn) {
        return authorizer.refusal(roleArn, accountId, SCHEDULER_PRINCIPAL, null,
                List.of(new Permission("redshift:" + action, clusterArn)));
    }
}

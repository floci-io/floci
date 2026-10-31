package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Decides whether the execution role of a scheduled action may take its snapshot. The role has to
 * trust the Redshift scheduler principal and its identity policies, boundary and SCPs have to allow
 * {@code redshift-serverless:CreateSnapshot} on the target. Only active with IAM enforcement on, as
 * for every other API call. A role of another account is refused, because the snapshot would
 * otherwise be taken on the strength of a role the action's account does not own.
 */
@ApplicationScoped
public class RedshiftServerlessScheduledActionAuthorizer {

    static final String CREATE_SNAPSHOT = "redshift-serverless:CreateSnapshot";
    private static final String SCHEDULER_PRINCIPAL = ServicePrincipals.of("scheduler.redshift");

    private final ServiceRoleAuthorizer authorizer;

    @Inject
    public RedshiftServerlessScheduledActionAuthorizer(ServiceRoleAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    /**
     * Returns why {@code roleArn} may not create a snapshot of {@code targetArn}, or nothing when it may.
     *
     * @param accountId the account that owns the action
     */
    public Optional<String> refusal(String roleArn, String accountId, String targetArn) {
        return authorizer.refusal(roleArn, accountId, SCHEDULER_PRINCIPAL, null,
                List.of(new Permission(CREATE_SNAPSHOT, targetArn)));
    }
}

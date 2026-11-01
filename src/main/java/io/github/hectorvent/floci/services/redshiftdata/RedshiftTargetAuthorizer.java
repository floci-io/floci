package io.github.hectorvent.floci.services.redshiftdata;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Decides whether the role of a Redshift Data API target may submit its SQL, for any service that
 * delivers events to one (EventBridge, Pipes). The delivering service assumes the target's role, so the
 * role has to trust that service's principal (with a condition on {@code aws:SourceArn} naming the rule
 * or pipe, if it has one), and its identity policies, permissions boundary and SCPs have to allow every
 * {@link Permission} the submission needs. Only active with IAM enforcement on, as for every other API
 * call. A role of another account is refused, because the account that owns the rule or pipe does not
 * own it.
 */
@ApplicationScoped
public class RedshiftTargetAuthorizer {

    private final EmulatorConfig config;
    private final ServiceRoleAuthorizer authorizer;

    @Inject
    public RedshiftTargetAuthorizer(EmulatorConfig config, ServiceRoleAuthorizer authorizer) {
        this.config = config;
        this.authorizer = authorizer;
    }

    /**
     * Returns why {@code roleArn} may not run the target, or nothing when it may.
     *
     * @param principal the service principal that assumes the role, such as {@code events.amazonaws.com}
     * @param accountId the account that owns the rule or pipe
     * @param sourceArn the rule or pipe, which a trust policy may require in {@code aws:SourceArn}; without
     *                  it such a condition does not match
     * @param permissions what the submission needs, each of which has to be allowed
     */
    public Optional<String> refusal(String principal, String roleArn, String accountId, String sourceArn,
                                    List<Permission> permissions) {
        if (!config.services().iam().enforcementEnabled()) {
            return Optional.empty();
        }
        if (roleArn == null || roleArn.isBlank()) {
            return Optional.of("A Redshift target requires an IAM role, and this one names none.");
        }
        return authorizer.refusal(roleArn, accountId, principal, sourceArn, permissions);
    }
}

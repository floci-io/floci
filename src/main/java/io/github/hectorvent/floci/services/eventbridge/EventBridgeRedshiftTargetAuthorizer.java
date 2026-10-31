package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Decides whether the role of an EventBridge Redshift Data API target may submit its SQL. EventBridge
 * assumes the target's role, so the role has to trust {@code events.amazonaws.com}, and its identity
 * policies, permissions boundary and SCPs have to allow every {@link Permission} the submission needs:
 * the Data API action itself and the action that authenticates it. Only active with IAM enforcement on,
 * as for every other API call. A role of another account is refused, because the rule's account does
 * not own it.
 */
@ApplicationScoped
public class EventBridgeRedshiftTargetAuthorizer {

    private static final String EVENTS_PRINCIPAL = ServicePrincipals.of("events");

    private final EmulatorConfig config;
    private final ServiceRoleAuthorizer authorizer;

    @Inject
    public EventBridgeRedshiftTargetAuthorizer(EmulatorConfig config, ServiceRoleAuthorizer authorizer) {
        this.config = config;
        this.authorizer = authorizer;
    }

    /**
     * Returns why {@code roleArn} may not run the target, or nothing when it may.
     *
     * @param accountId the account that owns the rule
     * @param ruleArn the rule that matched, which a trust policy may require in {@code aws:SourceArn}; without
     *                it such a condition does not match
     * @param permissions what the submission needs, each of which has to be allowed
     */
    public Optional<String> refusal(String roleArn, String accountId, String ruleArn,
                                    List<Permission> permissions) {
        if (!config.services().iam().enforcementEnabled()) {
            return Optional.empty();
        }
        if (roleArn == null || roleArn.isBlank()) {
            return Optional.of("A Redshift target requires an IAM role, and this one names none.");
        }
        return authorizer.refusal(roleArn, accountId, EVENTS_PRINCIPAL, ruleArn, permissions);
    }
}

package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
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

    /** One IAM action the target's role needs, on the resource it acts on. */
    public record Permission(String action, String resourceArn) {
    }

    private final EmulatorConfig config;
    private final IamService iamService;
    private final IamPolicyEvaluator evaluator;
    private final AssumeRolePolicyEvaluator trustEvaluator;
    private final Instance<ScpProvider> scpProvider;

    @Inject
    public EventBridgeRedshiftTargetAuthorizer(EmulatorConfig config, IamService iamService,
                                               IamPolicyEvaluator evaluator,
                                               AssumeRolePolicyEvaluator trustEvaluator,
                                               Instance<ScpProvider> scpProvider) {
        this.config = config;
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.trustEvaluator = trustEvaluator;
        this.scpProvider = scpProvider;
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
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(roleArn);
        } catch (IllegalArgumentException e) {
            return Optional.of("The role " + roleArn + " is not a valid ARN.");
        }
        if (!"iam".equals(parsed.service()) || !parsed.resource().startsWith("role/")) {
            return Optional.of("The role " + roleArn + " is not an IAM role.");
        }
        if (!accountId.equals(parsed.accountId())) {
            return Optional.of("The role " + roleArn + " belongs to another account.");
        }
        String roleName = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
        Optional<IamRole> role = iamService.findRole(accountId, roleName)
                .filter(found -> IamService.roleArnMatches(roleArn, found));
        if (role.isEmpty()) {
            return Optional.of("The role " + roleArn + " does not exist.");
        }
        if (!trustEvaluator.allowsService(role.get().getAssumeRolePolicyDocument(), EVENTS_PRINCIPAL,
                ruleArn, accountId)) {
            return Optional.of("The role " + roleArn + " does not trust " + EVENTS_PRINCIPAL + ".");
        }
        CallerContext caller = iamService.resolvePrincipalContext(role.get().getArn());
        List<List<String>> scpLevels = scpProvider.isResolvable()
                ? scpProvider.get().effectiveScpLevels(accountId) : null;
        if (scpLevels != null) {
            caller = caller.withScpLevels(scpLevels);
        }
        for (Permission permission : permissions) {
            if (evaluate(caller, role.get().getArn(), accountId, permission) != Decision.ALLOW) {
                return Optional.of("The role " + roleArn + " is not authorized to perform "
                        + permission.action() + " on " + permission.resourceArn() + ".");
            }
        }
        return Optional.empty();
    }

    private Decision evaluate(CallerContext caller, String roleArn, String accountId, Permission permission) {
        AwsArnUtils.Arn resource = AwsArnUtils.isArn(permission.resourceArn())
                ? AwsArnUtils.parse(permission.resourceArn()) : null;
        String region = resource != null ? resource.region() : null;
        String resourceAccount = resource != null ? resource.accountId() : accountId;
        Map<String, List<String>> context = IamConditionContextResolver.withGlobalContext(
                Map.of("aws:PrincipalArn", List.of(roleArn)),
                permission.resourceArn(), region, accountId, resourceAccount);
        return evaluator.evaluate(caller, List.of(), permission.action(), permission.resourceArn(), context);
    }
}

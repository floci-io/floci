package io.github.hectorvent.floci.services.redshiftserverless;

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

    private final EmulatorConfig config;
    private final IamService iamService;
    private final IamPolicyEvaluator evaluator;
    private final AssumeRolePolicyEvaluator trustEvaluator;
    private final Instance<ScpProvider> scpProvider;

    @Inject
    public RedshiftServerlessScheduledActionAuthorizer(EmulatorConfig config, IamService iamService,
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
     * Returns why {@code roleArn} may not create a snapshot of {@code targetArn}, or nothing when it may.
     *
     * @param accountId the account that owns the action
     */
    public Optional<String> refusal(String roleArn, String accountId, String targetArn) {
        if (!config.services().iam().enforcementEnabled()) {
            return Optional.empty();
        }
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(roleArn);
        } catch (IllegalArgumentException e) {
            return Optional.of("The execution role " + roleArn + " is not a valid ARN.");
        }
        if (!"iam".equals(parsed.service()) || !parsed.resource().startsWith("role/")) {
            return Optional.of("The execution role " + roleArn + " is not an IAM role.");
        }
        if (!accountId.equals(parsed.accountId())) {
            return Optional.of("The execution role " + roleArn + " belongs to another account.");
        }
        String roleName = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
        Optional<IamRole> role = iamService.findRole(accountId, roleName)
                .filter(found -> IamService.roleArnMatches(roleArn, found));
        if (role.isEmpty()) {
            return Optional.of("The execution role " + roleArn + " does not exist.");
        }
        if (!trustEvaluator.allowsService(role.get().getAssumeRolePolicyDocument(), SCHEDULER_PRINCIPAL,
                null, accountId)) {
            return Optional.of("The execution role " + roleArn + " does not trust " + SCHEDULER_PRINCIPAL + ".");
        }
        CallerContext caller = iamService.resolvePrincipalContext(role.get().getArn());
        List<List<String>> scpLevels = scpProvider.isResolvable()
                ? scpProvider.get().effectiveScpLevels(accountId) : null;
        if (scpLevels != null) {
            caller = caller.withScpLevels(scpLevels);
        }
        AwsArnUtils.Arn target = AwsArnUtils.isArn(targetArn) ? AwsArnUtils.parse(targetArn) : null;
        String targetRegion = target != null ? target.region() : null;
        String targetAccount = target != null ? target.accountId() : accountId;
        Map<String, List<String>> context = IamConditionContextResolver.withGlobalContext(
                Map.of("aws:PrincipalArn", List.of(role.get().getArn())),
                targetArn, targetRegion, accountId, targetAccount);
        Decision decision = evaluator.evaluate(caller, List.of(), CREATE_SNAPSHOT, targetArn, context);
        if (decision != Decision.ALLOW) {
            return Optional.of("The execution role " + roleArn + " is not authorized to perform "
                    + CREATE_SNAPSHOT + " on " + targetArn + ".");
        }
        return Optional.empty();
    }
}

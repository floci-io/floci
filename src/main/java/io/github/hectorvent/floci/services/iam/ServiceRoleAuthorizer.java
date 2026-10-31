package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether a customer's service role may be assumed by an AWS service to perform actions on
 * their behalf, such as EventBridge submitting statements to Redshift Data API or Redshift Serverless
 * taking scheduled snapshots. The role has to belong to the caller's account, trust the service
 * principal (with optional conditions such as {@code aws:SourceArn} and {@code aws:SourceAccount}),
 * and its identity policies, permissions boundary and SCPs must allow every required action.
 *
 * <p>Only active with IAM enforcement on. With it off, every role is allowed.
 */
@ApplicationScoped
public class ServiceRoleAuthorizer {

    /** One IAM action the role needs, on the resource it acts on. */
    public record Permission(String action, String resourceArn) {
    }

    private final EmulatorConfig config;
    private final IamService iamService;
    private final IamPolicyEvaluator evaluator;
    private final AssumeRolePolicyEvaluator trustEvaluator;
    private final Instance<ScpProvider> scpProvider;

    @Inject
    public ServiceRoleAuthorizer(EmulatorConfig config, IamService iamService,
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
     * Returns why {@code roleArn} may not be used by {@code servicePrincipal}, or empty when it may.
     *
     * @param roleArn the IAM role the service assumes
     * @param accountId the account that owns the resource and the role
     * @param servicePrincipal the AWS service principal that assumes the role (e.g. {@code events.amazonaws.com})
     * @param sourceArn optional ARN of the trigger or resource, checked against {@code aws:SourceArn} trust conditions
     * @param permissions the actions and resource ARNs the service needs to execute
     */
    public Optional<String> refusal(String roleArn, String accountId, String servicePrincipal,
                                    String sourceArn, List<Permission> permissions) {
        if (!config.services().iam().enforcementEnabled()) {
            return Optional.empty();
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
        if (!trustEvaluator.allowsService(role.get().getAssumeRolePolicyDocument(), servicePrincipal,
                sourceArn, accountId)) {
            return Optional.of("The role " + roleArn + " does not trust " + servicePrincipal + ".");
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

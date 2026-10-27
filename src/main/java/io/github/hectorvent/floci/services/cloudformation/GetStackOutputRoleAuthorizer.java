package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.AccessDeniedMessages;
import io.github.hectorvent.floci.services.iam.AccessDeniedMessages.PolicyType;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.SimulationDecision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether CloudFormation may read another stack's outputs through the role a
 * {@code Fn::GetStackOutput} names. On AWS CloudFormation assumes the role as the stack's
 * execution role, or without one as the caller of the stack operation, and reads the outputs with
 * DescribeStacks in a session named {@code GetStackOutput} (measured, eu-west-1, 2026-10-16).
 * Floci runs stack operations under no execution role and keeps no caller for them, so the role
 * has to exist, and with IAM enforcement on, its trust policy has to allow a principal in the
 * consuming account and its policies have to allow DescribeStacks on the stack, as
 * {@code AssumeRole} and {@code AppSyncDataSourceAuthorizer} evaluate a role.
 */
@ApplicationScoped
public class GetStackOutputRoleAuthorizer {

    private static final String DESCRIBE_STACKS = "cloudformation:DescribeStacks";
    private static final String SESSION_NAME = "GetStackOutput";

    private final IamService iamService;
    private final AssumeRolePolicyEvaluator trustEvaluator;
    private final IamPolicyEvaluator policyEvaluator;
    private final EmulatorConfig config;

    @Inject
    public GetStackOutputRoleAuthorizer(IamService iamService, AssumeRolePolicyEvaluator trustEvaluator,
                                        IamPolicyEvaluator policyEvaluator, EmulatorConfig config) {
        this.iamService = iamService;
        this.trustEvaluator = trustEvaluator;
        this.policyEvaluator = policyEvaluator;
        this.config = config;
    }

    /**
     * Returns the account whose stacks {@code roleArn} reads for a stack in {@code consumerAccount}.
     *
     * @throws AwsException STS's AccessDenied, as CloudFormation reports it, when the role does not
     *                      exist in {@code partition} or, with IAM enforcement on, does not trust
     *                      the consuming account
     */
    public String assume(String roleArn, String consumerAccount, String partition) {
        Optional<IamRole> role = role(roleArn, partition);
        if (role.isEmpty() || (enforced() && !trustEvaluator.allowsAnyPrincipalOf(
                role.get().getAssumeRolePolicyDocument(), consumerAccount, partition))) {
            throw new AwsException("AccessDenied", CloudFormationTemplateEngine.sdkErrorMessage(
                    AccessDeniedMessages.notAuthorized(
                            AwsArnUtils.Arn.global(partition, "iam", consumerAccount, "root").toString(),
                            "sts:AssumeRole", roleArn, null),
                    "AWSSecurityTokenService", 403, "AccessDenied"), 403);
        }
        return AwsArnUtils.parse(roleArn).accountId();
    }

    /**
     * With IAM enforcement on, refuses a read of {@code stackId} that the policies of the role
     * {@link #assume} accepted do not allow, with the AccessDenied DescribeStacks answers its
     * session (measured: "because no identity-based policy allows the cloudformation:DescribeStacks
     * action").
     */
    public void requireDescribeStacks(String roleArn, String stackId, String partition) {
        if (!enforced()) {
            return;
        }
        IamRole role = role(roleArn, partition).orElseThrow();
        CallerContext caller = iamService.resolvePrincipalContext(role.getArn());
        AwsArnUtils.Arn stack = AwsArnUtils.parse(stackId);
        SimulationDecision decision = policyEvaluator.simulatePrincipalPolicy(caller, DESCRIBE_STACKS, stackId,
                Map.of("aws:RequestedRegion", List.of(stack.region())));
        if (decision == SimulationDecision.ALLOWED) {
            return;
        }
        String session = AwsArnUtils.Arn.global(partition, "sts", stack.accountId(),
                "assumed-role/" + role.getRoleName() + "/" + SESSION_NAME).toString();
        throw new AwsException("AccessDenied", CloudFormationTemplateEngine.sdkErrorMessage(
                AccessDeniedMessages.notAuthorized(session, DESCRIBE_STACKS, stackId,
                        PolicyType.IDENTITY_BASED_POLICY.refusal(decision, DESCRIBE_STACKS, null)),
                "AmazonCloudFormation", 403, "AccessDenied"), 403);
    }

    private boolean enforced() {
        return config.services().iam().enforcementEnabled();
    }

    /** The role {@code roleArn} names, matched as STS {@code AssumeRole} matches a role ARN. */
    private Optional<IamRole> role(String roleArn, String partition) {
        if (!AwsArnUtils.isArn(roleArn)) {
            return Optional.empty();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
        if (!"iam".equals(arn.service()) || !partition.equals(arn.partition())
                || !arn.resource().startsWith("role/")) {
            return Optional.empty();
        }
        String roleName = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        return iamService.findRole(arn.accountId(), roleName)
                .filter(role -> IamService.roleArnMatches(roleArn, role));
    }
}

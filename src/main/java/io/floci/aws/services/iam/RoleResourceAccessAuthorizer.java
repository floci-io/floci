package io.floci.aws.services.iam;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.IamConditionContextResolver;
import io.floci.aws.services.iam.AccessDeniedMessages.PolicyType;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator.TrustDecision;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.floci.aws.services.iam.IamPolicyEvaluator.SimulationDecision;
import io.floci.aws.services.iam.ResourcePolicyProvider.ResourcePolicy;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether a role may perform an action on a resource, the way a service that calls another as that role is
 * judged: the role's identity policies, permissions boundary and its account's service control policies, together
 * with the resource's own policy. Within one account, the role's policies or the resource's policy can allow the
 * call; across accounts, both have to (IAM User Guide, cross-account policy evaluation logic). An explicit Deny in
 * any of them refuses it. A refusal carries the access denied message AWS words for the policy that refused.
 *
 * <p>Whether IAM is enforced at all is the caller's decision; this class always evaluates.
 */
@ApplicationScoped
public class RoleResourceAccessAuthorizer {

    private static final Logger LOG = Logger.getLogger(RoleResourceAccessAuthorizer.class);
    private static final String ANY_RESOURCE = "*";

    private final IamService iamService;
    private final IamPolicyEvaluator evaluator;
    private final Instance<ResourcePolicyProvider> resourcePolicies;
    private final Instance<ScpProvider> scpProvider;

    @Inject
    public RoleResourceAccessAuthorizer(IamService iamService, IamPolicyEvaluator evaluator,
                                        Instance<ResourcePolicyProvider> resourcePolicies,
                                        Instance<ScpProvider> scpProvider) {
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.resourcePolicies = resourcePolicies;
        this.scpProvider = scpProvider;
    }

    /**
     * Returns why {@code roleArn} may not perform {@code action} on {@code resourceArn}, as the service that owns
     * the resource words its access denied message, or nothing when it may.
     *
     * @param resourceArn     the resource, or {@code *} for an action with no resource-level permissions
     * @param requestedRegion the Region the call is made in, for the {@code aws:RequestedRegion} condition key
     * @param conditionKeys   the condition keys the called service adds to the global ones
     */
    public Optional<String> refusal(String roleArn, String action, String resourceArn, String requestedRegion,
                                    Map<String, List<String>> conditionKeys) {
        AwsArnUtils.Arn resource = ANY_RESOURCE.equals(resourceArn) ? null : AwsArnUtils.parse(resourceArn);
        Optional<IamRole> invokingRole = role(roleArn);
        if (invokingRole.isEmpty()) {
            LOG.infov("Role {0} names no existing role, so it cannot invoke {1}", roleArn, resourceArn);
            return Optional.of(denied(roleArn, action, resourceArn, PolicyType.IDENTITY_BASED_POLICY.noAllow(action)));
        }
        // The role is evaluated as itself, under the ARN IAM holds for it, whichever form names it.
        String invokingRoleArn = invokingRole.get().getArn();
        CallerContext role = iamService.resolvePrincipalContext(invokingRoleArn);
        AwsArnUtils.Arn roleId = AwsArnUtils.parse(invokingRoleArn);
        List<List<String>> scpLevels = scpLevels(roleId.accountId());

        List<ResourcePolicy> policies = resource == null ? List.of()
                : ResourcePolicyProviders.policiesFor(resourcePolicies, resource.service(), action, resourceArn);
        String ownerAccount = policies.isEmpty()
                ? (resource == null ? null : resource.accountId()) : policies.getFirst().ownerAccountId();
        List<String> documents = policies.stream()
                .map(ResourcePolicy::policyDocument)
                .filter(document -> document != null && !document.isBlank())
                .toList();
        Map<String, List<String>> serviceContext = new LinkedHashMap<>(conditionKeys);
        serviceContext.put("aws:PrincipalArn", List.of(invokingRoleArn));
        Map<String, List<String>> context = IamConditionContextResolver.withGlobalContext(
                serviceContext, resourceArn, requestedRegion, roleId.accountId(), ownerAccount);

        RequestPrincipal session = RequestPrincipal.roleSession(sessionArn(invokingRoleArn), invokingRoleArn);
        ResourcePolicyDecision resourcePolicy = evaluator.evaluateResourcePolicyFor(
                documents.isEmpty() ? null : documents, session, action, resourceArn, context);
        ResourceAccountRelationship relationship = relationship(roleId.accountId(), ownerAccount);
        Decision decision = evaluator.evaluateResolvedResourcePolicy(
                role.withScpLevels(scpLevels), resourcePolicy, relationship, action, resourceArn, context);
        if (decision == Decision.ALLOW) {
            return Optional.empty();
        }
        return Optional.of(denied(invokingRoleArn, action, resourceArn, reason(role, scpLevels, action, resourceArn,
                context, relationship, ResourceGrant.of(resourcePolicy))));
    }

    /**
     * The role {@code roleArn} names, matched as STS {@code AssumeRole} matches a role ARN
     * ({@link IamService#roleArnMatches}). A role ARN that names no role, or names one under
     * another path, is not that role, and a grant written for the ARN must not admit it.
     */
    public Optional<IamRole> role(String roleArn) {
        if (!AwsArnUtils.isArn(roleArn)) {
            return Optional.empty();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
        String roleName = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        return iamService.findRole(arn.accountId(), roleName)
                .filter(role -> IamService.roleArnMatches(roleArn, role));
    }

    public List<List<String>> scpLevels(String accountId) {
        return scpProvider.isResolvable() ? scpProvider.get().effectiveScpLevels(accountId) : null;
    }

    /**
     * A resource whose account is unknown, an action on {@code *} or an S3 ARN of a bucket S3 does
     * not hold, is judged within the caller's account: nothing says it belongs to another.
     */
    public static ResourceAccountRelationship relationship(String callerAccount, String resourceAccount) {
        return resourceAccount == null || resourceAccount.isBlank() || callerAccount.equals(resourceAccount)
                ? ResourceAccountRelationship.SAME_ACCOUNT : ResourceAccountRelationship.CROSS_ACCOUNT;
    }

    /**
     * The context that ends the access denied message (IAM User Guide, "Access denied error message
     * examples"): the first policy type that refuses the call, explicit denials before implicit ones,
     * each in the order AWS evaluates the types: service control policies, the resource's policy,
     * identity-based policies, then the permissions boundary.
     */
    public String reason(CallerContext role, List<List<String>> scpLevels, String action, String resource,
                         Map<String, List<String>> context, ResourceAccountRelationship relationship,
                         ResourceGrant grant) {
        List<SimulationDecision> scps = scpLevels == null ? List.of() : scpLevels.stream()
                .filter(level -> !level.isEmpty())
                .map(level -> simulate(level, action, resource, context))
                .toList();
        SimulationDecision identity = simulate(role.identityPolicies(), action, resource, context);
        SimulationDecision boundary = role.boundaryPolicyDocument() == null
                ? SimulationDecision.ALLOWED
                : simulate(List.of(role.boundaryPolicyDocument()), action, resource, context);
        boolean crossAccount = relationship == ResourceAccountRelationship.CROSS_ACCOUNT;

        if (scps.contains(SimulationDecision.EXPLICIT_DENY)) {
            return PolicyType.SERVICE_CONTROL_POLICY.explicitDeny();
        }
        if (grant.explicitDeny()) {
            return grant.type().explicitDeny();
        }
        if (identity == SimulationDecision.EXPLICIT_DENY) {
            return PolicyType.IDENTITY_BASED_POLICY.explicitDeny();
        }
        if (boundary == SimulationDecision.EXPLICIT_DENY) {
            return PolicyType.PERMISSIONS_BOUNDARY.explicitDeny();
        }
        if (scps.contains(SimulationDecision.IMPLICIT_DENY)) {
            return PolicyType.SERVICE_CONTROL_POLICY.noAllow(action);
        }
        if ((grant.required() || crossAccount) && !grant.allows()) {
            return grant.type().noAllow(action);
        }
        if (identity != SimulationDecision.ALLOWED && (crossAccount || !grant.sufficient())) {
            return PolicyType.IDENTITY_BASED_POLICY.noAllow(action);
        }
        return PolicyType.PERMISSIONS_BOUNDARY.noAllow(action);
    }

    private SimulationDecision simulate(List<String> documents, String action, String resource,
                                        Map<String, List<String>> context) {
        return evaluator.simulatePrincipalPolicy(CallerContext.of(documents), action, resource, context);
    }

    public static String denied(String roleArn, String action, String resource, String reason) {
        return AccessDeniedMessages.notAuthorized(sessionArn(roleArn), action, resource, reason);
    }

    /**
     * The role's session, which makes the call, as an access denied message writes it:
     * {@code arn:aws:sts::account-id:assumed-role/role-name}.
     */
    public static String sessionArn(String roleArn) {
        try {
            AwsArnUtils.Arn role = AwsArnUtils.parse(roleArn);
            String roleName = role.resource().substring(role.resource().lastIndexOf('/') + 1);
            return AwsArnUtils.Arn.global(role.partition(), "sts", role.accountId(), "assumed-role/" + roleName)
                    .toString();
        } catch (IllegalArgumentException ignored) {
            // A role ARN that does not parse names no session, so the message names the ARN as given.
            return roleArn;
        }
    }

    /**
     * What the resource's own policy said about a call, and how the access denied message names it.
     *
     * @param allows     whether it allows the call at all
     * @param sufficient whether, within one account, that allow grants the call without the
     *                   caller's identity-based policies
     * @param required   whether the call needs it to allow even within one account, as a role's
     *                   trust policy does
     * @param type       the policy type, as the access denied message names it
     */
    public record ResourceGrant(boolean explicitDeny, boolean allows, boolean sufficient, boolean required,
                                PolicyType type) {

        public static ResourceGrant of(ResourcePolicyDecision resourcePolicy) {
            boolean sufficient = resourcePolicy == ResourcePolicyDecision.ALLOW
                    || resourcePolicy == ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER;
            return new ResourceGrant(resourcePolicy == ResourcePolicyDecision.EXPLICIT_DENY,
                    sufficient || resourcePolicy == ResourcePolicyDecision.ALLOW_ACCOUNT, sufficient, false,
                    PolicyType.RESOURCE_BASED_POLICY);
        }

        public static ResourceGrant of(TrustDecision trust) {
            return new ResourceGrant(trust == TrustDecision.EXPLICIT_DENY,
                    trust == TrustDecision.ALLOW || trust == TrustDecision.ALLOW_ACCOUNT,
                    trust == TrustDecision.ALLOW, true, PolicyType.ROLE_TRUST_POLICY);
        }
    }
}

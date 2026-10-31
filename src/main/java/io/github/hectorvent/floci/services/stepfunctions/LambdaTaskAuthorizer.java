package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.services.iam.AccessDeniedMessages;
import io.github.hectorvent.floci.services.iam.AccessDeniedMessages.PolicyType;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator.TrustDecision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.SimulationDecision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import io.github.hectorvent.floci.services.iam.RequestPrincipal;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider.ResourcePolicy;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.lambda.LambdaResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides a Lambda Task's call to its function. The task calls Invoke as its role: the state
 * machine's execution role (Step Functions Developer Guide, "I can't invoke a Lambda function from
 * another account": the role "must have the right permissions to invoke cross-account Lambda
 * functions"), or the role its {@code Credentials} name, which the execution role must assume
 * first (Developer Guide, Task state "Credentials"). Within one account, the role's policies or the
 * resource's policy can allow a call; across accounts, both have to (IAM User Guide, cross-account
 * policy evaluation logic). The role's permissions boundary and its account's service control
 * policies limit it, and an explicit Deny in any of them refuses it.
 */
@ApplicationScoped
public class LambdaTaskAuthorizer {

    private static final Logger LOG = Logger.getLogger(LambdaTaskAuthorizer.class);
    private static final String INVOKE = "lambda:InvokeFunction";
    private static final String ASSUME_ROLE = "sts:AssumeRole";

    private final IamService iamService;
    private final IamPolicyEvaluator evaluator;
    private final AssumeRolePolicyEvaluator trustEvaluator;
    private final LambdaResourcePolicyProvider functionPolicies;
    private final Instance<ScpProvider> scpProvider;

    @Inject
    public LambdaTaskAuthorizer(IamService iamService, IamPolicyEvaluator evaluator,
                                AssumeRolePolicyEvaluator trustEvaluator,
                                LambdaResourcePolicyProvider functionPolicies, Instance<ScpProvider> scpProvider) {
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.trustEvaluator = trustEvaluator;
        this.functionPolicies = functionPolicies;
        this.scpProvider = scpProvider;
    }

    /**
     * Returns why {@code roleArn} may not invoke {@code functionArn}, as Lambda's access denied
     * message words it, or nothing when it may.
     *
     * @param functionArn the function, version or alias the task invokes, qualified as it names it
     */
    public Optional<String> refusal(String roleArn, String functionArn) {
        return refusal(roleArn, INVOKE, functionArn);
    }

    /** Applies the task role's identity policies, boundary and SCPs to the requested action. */
    public Optional<String> refusal(String roleArn, String action, String resourceArn) {
        AwsArnUtils.Arn resource = AwsArnUtils.parse(resourceArn);
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

        List<ResourcePolicy> policies = "lambda".equals(resource.service())
                ? functionPolicies.getResourcePolicies("lambda", resourceArn) : List.of();
        String ownerAccount = policies.isEmpty() ? resource.accountId() : policies.getFirst().ownerAccountId();
        List<String> documents = policies.stream()
                .map(ResourcePolicy::policyDocument)
                .filter(document -> document != null && !document.isBlank())
                .toList();
        Map<String, List<String>> context = IamConditionContextResolver.withGlobalContext(
                Map.of("aws:PrincipalArn", List.of(invokingRoleArn)), resourceArn, resource.region(),
                roleId.accountId(), ownerAccount);

        RequestPrincipal session = RequestPrincipal.roleSession(sessionArn(invokingRoleArn), invokingRoleArn);
        ResourcePolicyDecision functionPolicy = evaluator.evaluateResourcePolicyFor(
                documents.isEmpty() ? null : documents, session, action, resourceArn, context);
        ResourceAccountRelationship relationship = relationship(roleId.accountId(), ownerAccount);
        Decision decision = evaluator.evaluateResolvedResourcePolicy(
                role.withScpLevels(scpLevels), functionPolicy, relationship, action, resourceArn, context);
        if (decision == Decision.ALLOW) {
            return Optional.empty();
        }
        return Optional.of(denied(invokingRoleArn, action, resourceArn, reason(role, scpLevels, action, resourceArn,
                context, relationship, ResourceGrant.of(functionPolicy))));
    }

    /**
     * Returns why the execution role {@code executionRoleArn} may not assume {@code roleArn}, the
     * role a task's {@code Credentials} name, as STS's access denied message words it, or nothing
     * when it may. The role's trust policy has to allow the execution role, and the execution
     * role's own policies have to allow {@code sts:AssumeRole} on it when the role is in another
     * account or the trust policy names only the execution role's account. Step Functions passes
     * the state machine's ARN as {@code sts:ExternalId}, which the Developer Guide's cross-account
     * tutorial has the trust policy check.
     */
    public Optional<String> assumeRefusal(String executionRoleArn, String roleArn, String stateMachineArn) {
        Optional<IamRole> executionRole = role(executionRoleArn);
        if (executionRole.isEmpty()) {
            LOG.infov("Execution role {0} names no existing role, so it cannot assume {1}", executionRoleArn, roleArn);
            return Optional.of(denied(executionRoleArn, ASSUME_ROLE, roleArn,
                    PolicyType.IDENTITY_BASED_POLICY.noAllow(ASSUME_ROLE)));
        }
        String callerRoleArn = executionRole.get().getArn();
        Optional<IamRole> target = role(roleArn);
        if (target.isEmpty()) {
            // STS refuses a role that does not exist as it refuses one the caller may not assume,
            // and no policy of the role took part, so the message names none.
            LOG.infov("Role {0} named by a task's Credentials does not exist", roleArn);
            return Optional.of(denied(callerRoleArn, ASSUME_ROLE, roleArn, null));
        }
        String targetRoleArn = target.get().getArn();
        CallerContext caller = iamService.resolvePrincipalContext(callerRoleArn);
        AwsArnUtils.Arn callerId = AwsArnUtils.parse(callerRoleArn);
        String roleAccount = AwsArnUtils.parse(targetRoleArn).accountId();
        List<List<String>> scpLevels = scpLevels(callerId.accountId());
        Map<String, List<String>> context = IamConditionContextResolver.withGlobalContext(
                Map.of("aws:PrincipalArn", List.of(callerRoleArn), "sts:ExternalId", List.of(stateMachineArn)),
                targetRoleArn, AwsArnUtils.parse(stateMachineArn).region(), callerId.accountId(), roleAccount);

        // Step Functions assumes the execution role on the state machine's behalf, so the caller
        // is a session of it and carries AssumedRole as its aws:PrincipalType. That path mints no
        // session name, and a session's aws:userid is role-id:session-name, so it and aws:username
        // stay absent, which is what makes a condition on either of them not match.
        CallerArns trustCaller = new CallerArns(sessionArn(callerRoleArn), callerRoleArn,
                CallerArns.TYPE_ASSUMED_ROLE, null, null);
        TrustDecision trust = trustEvaluator.decide(target.get().getAssumeRolePolicyDocument(), trustCaller,
                callerId.accountId(), context);
        ResourceAccountRelationship relationship = relationship(callerId.accountId(), roleAccount);
        // A grant to the execution role's account delegates to that account, so within it the
        // role's own policies have to allow the call too, as they do across accounts anyway.
        ResourcePolicyDecision trustGrant = switch (trust) {
            case ALLOW -> ResourcePolicyDecision.ALLOW;
            case ALLOW_ACCOUNT -> ResourcePolicyDecision.ALLOW_ACCOUNT;
            case EXPLICIT_DENY -> ResourcePolicyDecision.EXPLICIT_DENY;
            case NEUTRAL -> ResourcePolicyDecision.NEUTRAL;
        };
        ResourceGrant grant = ResourceGrant.of(trust);
        Decision decision = grant.allows()
                ? evaluator.evaluateResolvedResourcePolicy(caller.withScpLevels(scpLevels), trustGrant, relationship,
                        ASSUME_ROLE, targetRoleArn, context)
                : Decision.DENY;
        if (decision == Decision.ALLOW) {
            return Optional.empty();
        }
        return Optional.of(denied(callerRoleArn, ASSUME_ROLE, roleArn,
                reason(caller, scpLevels, ASSUME_ROLE, targetRoleArn, context, relationship, grant)));
    }

    /**
     * The role {@code roleArn} names, matched as STS {@code AssumeRole} matches a role ARN
     * ({@link IamService#roleArnMatches}). A role ARN that names no role, or names one under
     * another path, is not that role, and a grant written for the ARN must not admit it.
     */
    private Optional<IamRole> role(String roleArn) {
        if (!AwsArnUtils.isArn(roleArn)) {
            return Optional.empty();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
        String roleName = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        return iamService.findRole(arn.accountId(), roleName)
                .filter(role -> IamService.roleArnMatches(roleArn, role));
    }

    private List<List<String>> scpLevels(String accountId) {
        return scpProvider.isResolvable() ? scpProvider.get().effectiveScpLevels(accountId) : null;
    }

    private static ResourceAccountRelationship relationship(String callerAccount, String resourceAccount) {
        return callerAccount.equals(resourceAccount)
                ? ResourceAccountRelationship.SAME_ACCOUNT : ResourceAccountRelationship.CROSS_ACCOUNT;
    }

    /**
     * The context that ends the access denied message (IAM User Guide, "Access denied error message
     * examples"): the first policy type that refuses the call, explicit denials before implicit ones,
     * each in the order AWS evaluates the types: service control policies, the resource's policy,
     * identity-based policies, then the permissions boundary.
     */
    private String reason(CallerContext role, List<List<String>> scpLevels, String action, String resource,
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

    private static String denied(String roleArn, String action, String resource, String reason) {
        return AccessDeniedMessages.notAuthorized(sessionArn(roleArn), action, resource, reason);
    }

    /**
     * The role's session, which makes the call, as the Step Functions Developer Guide writes it in
     * an access denied message: {@code arn:aws:sts::account-id:assumed-role/StepFunctions-MyStateMachine-role}.
     */
    private static String sessionArn(String roleArn) {
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
    private record ResourceGrant(boolean explicitDeny, boolean allows, boolean sufficient, boolean required,
                                 PolicyType type) {

        static ResourceGrant of(ResourcePolicyDecision functionPolicy) {
            boolean sufficient = functionPolicy == ResourcePolicyDecision.ALLOW
                    || functionPolicy == ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER;
            return new ResourceGrant(functionPolicy == ResourcePolicyDecision.EXPLICIT_DENY,
                    sufficient || functionPolicy == ResourcePolicyDecision.ALLOW_ACCOUNT, sufficient, false,
                    PolicyType.RESOURCE_BASED_POLICY);
        }

        static ResourceGrant of(TrustDecision trust) {
            return new ResourceGrant(trust == TrustDecision.EXPLICIT_DENY,
                    trust == TrustDecision.ALLOW || trust == TrustDecision.ALLOW_ACCOUNT,
                    trust == TrustDecision.ALLOW, true, PolicyType.ROLE_TRUST_POLICY);
        }
    }
}

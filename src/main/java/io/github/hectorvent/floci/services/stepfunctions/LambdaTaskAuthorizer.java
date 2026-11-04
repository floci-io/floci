package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
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
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider.ResourcePolicy;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProviders;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
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
 *
 * <p>{@link #authorize} is the same decision for any other integration's call, ahead of which it
 * requires the execution role to exist and to trust Step Functions.
 */
@ApplicationScoped
public class LambdaTaskAuthorizer {

    private static final Logger LOG = Logger.getLogger(LambdaTaskAuthorizer.class);
    private static final String INVOKE = "lambda:InvokeFunction";
    private static final String ASSUME_ROLE = "sts:AssumeRole";
    private static final String ANY_RESOURCE = "*";

    private final IamService iamService;
    private final IamPolicyEvaluator evaluator;
    private final AssumeRolePolicyEvaluator trustEvaluator;
    private final Instance<ResourcePolicyProvider> resourcePolicies;
    private final Instance<ScpProvider> scpProvider;

    @Inject
    public LambdaTaskAuthorizer(IamService iamService, IamPolicyEvaluator evaluator,
                                AssumeRolePolicyEvaluator trustEvaluator,
                                Instance<ResourcePolicyProvider> resourcePolicies,
                                Instance<ScpProvider> scpProvider) {
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.trustEvaluator = trustEvaluator;
        this.resourcePolicies = resourcePolicies;
        this.scpProvider = scpProvider;
    }

    /**
     * One IAM action a task's call needs.
     *
     * @param action        the IAM action, such as {@code sqs:SendMessage}
     * @param resourceArn   the resource it acts on, or {@code *} for an action with no resource-level
     *                      permissions. An S3 ARN carries no account: the bucket's owner comes from S3
     * @param conditionKeys the condition keys the integrated service adds to the global ones
     */
    public record Permission(String action, String resourceArn, Map<String, List<String>> conditionKeys) {

        public Permission(String action, String resourceArn) {
            this(action, resourceArn, Map.of());
        }
    }

    /**
     * Decides a task's call to its integrated service, throwing the failure the task records. The
     * execution role has to exist and to trust Step Functions, then to assume the role the task's
     * {@code Credentials} name, if any, and the role the task ends up with has to be allowed every
     * permission.
     *
     * @param credentialsRoleArn the role the task's {@code Credentials} resolved to, or null
     * @param errorPrefix        the integration's error prefix, dot included, such as {@code SQS.}
     * @throws AslExecutor.FailStateException {@code States.Permissions} for the execution role,
     *         {@code States.TaskFailed} for a refused assumption, and
     *         {@code <errorPrefix>AccessDeniedException} for a refused permission
     */
    void authorize(StateMachine machine, String credentialsRoleArn, String errorPrefix, List<Permission> permissions) {
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        IamRole executionRole = role(machine.getRoleArn()).orElseThrow(
                () -> new AslExecutor.FailStateException("States.Permissions",
                        "The execution role " + machine.getRoleArn() + " does not exist."));
        if (!trustEvaluator.allowsService(executionRole.getAssumeRolePolicyDocument(), ServicePrincipals.of("states"),
                machine.getStateMachineArn(), owner.accountId())) {
            throw new AslExecutor.FailStateException("States.Permissions",
                    "The execution role " + machine.getRoleArn() + " does not trust Step Functions.");
        }
        String effectiveRoleArn = machine.getRoleArn();
        if (credentialsRoleArn != null) {
            if (assumeRefusal(machine.getRoleArn(), credentialsRoleArn, machine.getStateMachineArn()).isPresent()) {
                throw NestedTaskCredentials.assumptionDenied(machine, credentialsRoleArn);
            }
            effectiveRoleArn = credentialsRoleArn;
        }
        for (Permission permission : permissions) {
            Optional<String> refusal = refusal(effectiveRoleArn, permission.action(), permission.resourceArn(),
                    owner.region(), permission.conditionKeys());
            if (refusal.isPresent()) {
                throw new AslExecutor.FailStateException(errorPrefix + "AccessDeniedException", refusal.get());
            }
        }
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
        return refusal(roleArn, action, resourceArn, AwsArnUtils.regionOrDefault(resourceArn, null), Map.of());
    }

    private Optional<String> refusal(String roleArn, String action, String resourceArn, String requestedRegion,
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

    /**
     * A resource whose account is unknown, an action on {@code *} or an S3 ARN of a bucket S3 does
     * not hold, is judged within the caller's account: nothing says it belongs to another.
     */
    private static ResourceAccountRelationship relationship(String callerAccount, String resourceAccount) {
        return resourceAccount == null || resourceAccount.isBlank() || callerAccount.equals(resourceAccount)
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

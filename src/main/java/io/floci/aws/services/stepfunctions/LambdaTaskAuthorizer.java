package io.floci.aws.services.stepfunctions;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.IamConditionContextResolver;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.iam.AccessDeniedMessages.PolicyType;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator.TrustDecision;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.IamService.CallerArns;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import io.floci.aws.services.iam.ResourcePolicyProviders;
import io.floci.aws.services.iam.RoleResourceAccessAuthorizer;
import io.floci.aws.services.iam.RoleResourceAccessAuthorizer.ResourceGrant;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.stepfunctions.model.StateMachine;
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
    private final RoleResourceAccessAuthorizer access;

    @Inject
    public LambdaTaskAuthorizer(IamService iamService, IamPolicyEvaluator evaluator,
                                AssumeRolePolicyEvaluator trustEvaluator,
                                Instance<ResourcePolicyProvider> resourcePolicies,
                                RoleResourceAccessAuthorizer access) {
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.trustEvaluator = trustEvaluator;
        this.resourcePolicies = resourcePolicies;
        this.access = access;
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
     * @param deniedError        the error a refused permission fails with, such as {@code SQS.AccessDeniedException}
     * @throws AslExecutor.FailStateException {@code States.Permissions} for the execution role,
     *         {@code States.TaskFailed} for a refused assumption, and {@code deniedError} for a refused permission
     */
    void authorize(StateMachine machine, String credentialsRoleArn, String deniedError, List<Permission> permissions) {
        authorize(machine, credentialsRoleArn, deniedError, permissions, true);
    }

    /**
     * As {@link #authorize(StateMachine, String, String, List)}.
     *
     * @param checkExecutionRole false for an integration whose tasks do not refuse an execution role for not existing
     *                           or not trusting Step Functions: a nested execution, which only needs the permissions
     */
    void authorize(StateMachine machine, String credentialsRoleArn, String deniedError, List<Permission> permissions,
                   boolean checkExecutionRole) {
        decide(machine, credentialsRoleArn, deniedError, permissions, checkExecutionRole);
    }

    /**
     * Decides the S3 calls a Distributed Map makes as its execution role, which has no error prefix of its own: a
     * refused permission fails the state with the error its reader or writer fails with on any other S3 failure.
     * The execution role has to exist and to trust Step Functions, as for a task, and is refused with
     * {@code States.Permissions}. A Map has no {@code Credentials} to assume.
     *
     * @param refusedError the error a refused permission fails with
     * @throws AslExecutor.FailStateException {@code States.Permissions} for the execution role, and
     *         {@code refusedError} for a refused permission
     */
    void authorizeMapS3(StateMachine machine, String refusedError, List<Permission> permissions) {
        decide(machine, null, refusedError, permissions, true);
    }

    private void decide(StateMachine machine, String credentialsRoleArn, String refusedError,
                        List<Permission> permissions, boolean checkExecutionRole) {
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        if (checkExecutionRole) {
            IamRole executionRole = access.role(machine.getRoleArn()).orElseThrow(
                    () -> new AslExecutor.FailStateException("States.Permissions",
                            "The execution role " + machine.getRoleArn() + " does not exist."));
            if (!trustEvaluator.allowsService(executionRole,
                    ServicePrincipals.of("states"), machine.getStateMachineArn(), owner.accountId())) {
                throw new AslExecutor.FailStateException("States.Permissions",
                        "The execution role " + machine.getRoleArn() + " does not trust Step Functions.");
            }
        }
        String effectiveRoleArn = machine.getRoleArn();
        if (credentialsRoleArn != null) {
            if (assumeRefusal(machine.getRoleArn(), credentialsRoleArn, machine.getStateMachineArn()).isPresent()) {
                throw NestedTaskCredentials.assumptionDenied(machine, credentialsRoleArn);
            }
            effectiveRoleArn = credentialsRoleArn;
        }
        for (Permission permission : permissions) {
            Optional<String> refusal = refusal(effectiveRoleArn, permission.action(), evaluatedOn(permission.resourceArn()),
                    owner.region(), permission.conditionKeys());
            if (refusal.isPresent()) {
                throw new AslExecutor.FailStateException(refusedError, refusal.get());
            }
        }
    }

    /**
     * The ARN a permission is evaluated on, the one a direct request is: S3's global bucket namespace reaches a
     * bucket in another partition, whose ARN is in the bucket's partition and not the state machine's.
     */
    private String evaluatedOn(String resourceArn) {
        return ANY_RESOURCE.equals(resourceArn) ? resourceArn : ResourcePolicyProviders.resolveResourceArn(
                resourcePolicies, AwsArnUtils.parse(resourceArn).service(), resourceArn);
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
        return access.refusal(roleArn, action, resourceArn, requestedRegion, conditionKeys);
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
        Optional<IamRole> executionRole = access.role(executionRoleArn);
        if (executionRole.isEmpty()) {
            LOG.infov("Execution role {0} names no existing role, so it cannot assume {1}", executionRoleArn, roleArn);
            return Optional.of(RoleResourceAccessAuthorizer.denied(executionRoleArn, ASSUME_ROLE, roleArn,
                    PolicyType.IDENTITY_BASED_POLICY.noAllow(ASSUME_ROLE)));
        }
        String callerRoleArn = executionRole.get().getArn();
        Optional<IamRole> target = access.role(roleArn);
        if (target.isEmpty()) {
            // STS refuses a role that does not exist as it refuses one the caller may not assume,
            // and no policy of the role took part, so the message names none.
            LOG.infov("Role {0} named by a task''s Credentials does not exist", roleArn);
            return Optional.of(RoleResourceAccessAuthorizer.denied(callerRoleArn, ASSUME_ROLE, roleArn, null));
        }
        String targetRoleArn = target.get().getArn();
        CallerContext caller = iamService.resolvePrincipalContext(callerRoleArn);
        AwsArnUtils.Arn callerId = AwsArnUtils.parse(callerRoleArn);
        String roleAccount = AwsArnUtils.parse(targetRoleArn).accountId();
        List<List<String>> scpLevels = access.scpLevels(callerId.accountId());
        Map<String, List<String>> context = IamConditionContextResolver.withResourceTags(
                IamConditionContextResolver.withGlobalContext(
                        Map.of("aws:PrincipalArn", List.of(callerRoleArn), "sts:ExternalId", List.of(stateMachineArn)),
                        targetRoleArn, AwsArnUtils.parse(stateMachineArn).region(), callerId.accountId(), roleAccount),
                IamConditionContextResolver.resourceTagKeys(targetRoleArn, target.get().getTags()));

        // Step Functions assumes the execution role on the state machine's behalf, so the caller
        // is a session of it and carries AssumedRole as its aws:PrincipalType. That path mints no
        // session name, and a session's aws:userid is role-id:session-name, so it and aws:username
        // stay absent, which is what makes a condition on either of them not match.
        CallerArns trustCaller = new CallerArns(RoleResourceAccessAuthorizer.sessionArn(callerRoleArn), callerRoleArn,
                CallerArns.TYPE_ASSUMED_ROLE, null, null)
                .withOrganization(iamService.organizationContextOf(callerId.accountId()).orElse(null));
        TrustDecision trust = trustEvaluator.decide(target.get().getAssumeRolePolicyDocument(), trustCaller,
                callerId.accountId(), context);
        ResourceAccountRelationship relationship = RoleResourceAccessAuthorizer.relationship(callerId.accountId(), roleAccount);
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
        return Optional.of(RoleResourceAccessAuthorizer.denied(callerRoleArn, ASSUME_ROLE, roleArn,
                access.reason(caller, scpLevels, ASSUME_ROLE, targetRoleArn, context, relationship, grant)));
    }
}

package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.Set;

/**
 * The account a task's calls run in when its {@code Credentials} name a role. The execution role assumes that role
 * "before invoking the specified Resource", which Task types using Lambda functions and supported AWS services allow
 * (Developer Guide, Task state), and the calls then reach "resources that the target account owns", the role being
 * in one partition with the state machine (Developer Guide, "Accessing cross-account AWS resources in Step
 * Functions"). Activities take no {@code Credentials}; HTTP tasks, for which AWS does not say, keep the state
 * machine's account. The class keeps its name from when only nested executions were routed.
 */
@ApplicationScoped
public class NestedTaskCredentials {
    private final IamService iam;
    private final EmulatorConfig config;
    private final LambdaTaskAuthorizer authorizer;

    @Inject
    public NestedTaskCredentials(IamService iam, EmulatorConfig config, LambdaTaskAuthorizer authorizer) {
        this.iam = iam;
        this.config = config;
        this.authorizer = authorizer;
    }

    /**
     * The account of the role {@code credentials} name, checked as the role the task's calls are made as: it has to be
     * an IAM role of the execution's partition that exists. Under IAM enforcement the execution role has to be able to
     * assume it when it is in another account or the task is a nested execution, whose target has to be in the role's
     * account and allowed to it; another task's own authorization decides an assumption within one account.
     *
     * @param resource the state's {@code Resource}, which {@link #routes} accepts
     */
    String account(JsonNode credentials, String resource, JsonNode input, StateMachine machine) {
        StatesIntegration integration = StatesIntegration.parse(resource).orElse(null);
        if (!routes(resource)) {
            throw new AslExecutor.FailStateException("States.Runtime",
                    "Task Credentials account routing is supported only for Lambda and AWS service integrations");
        }
        validateShape(credentials);
        String roleArn = credentials.path("RoleArn").asText();
        AwsArnUtils.Arn role;
        try {
            role = AwsArnUtils.parse(roleArn);
        } catch (IllegalArgumentException error) {
            throw new AslExecutor.FailStateException("States.Runtime", "Task Credentials RoleArn is invalid");
        }
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        if (!"iam".equals(role.service()) || !role.region().isEmpty()
                || !role.resource().startsWith("role/") || !role.accountId().matches("\\d{12}")
                || !role.partition().equals(owner.partition())) {
            throw new AslExecutor.FailStateException("States.Runtime",
                    "Task Credentials must name an IAM role in the execution partition");
        }
        String name = role.resource().substring(role.resource().lastIndexOf('/') + 1);
        if (iam.findRole(role.accountId(), name).filter(found -> IamService.roleArnMatches(roleArn, found)).isEmpty()) {
            throw assumptionDenied(machine, roleArn);
        }
        if (!config.services().iam().enforcementEnabled()) {
            return role.accountId();
        }
        if (!isNested(integration)) {
            if (!role.accountId().equals(owner.accountId())) {
                Optional<String> refusal = authorizer.assumeRefusal(machine.getRoleArn(), roleArn,
                        machine.getStateMachineArn());
                if (refusal.isPresent()) {
                    // The Lambda task's own check refuses an assumption this way, so the error stays the same.
                    throw isLambda(resource, integration)
                            ? new AslExecutor.FailStateException("States.Permissions", refusal.get())
                            : assumptionDenied(machine, roleArn);
                }
            }
            return role.accountId();
        }
        if (authorizer.assumeRefusal(machine.getRoleArn(), roleArn, machine.getStateMachineArn()).isPresent()) {
            throw assumptionDenied(machine, roleArn);
        }
        validateTarget(resource, input, machine, role.accountId());
        String target = input.path("StateMachineArn").asText(null);
        if (AwsArnUtils.isArn(target)) {
            String action = integration.isSdk("sfn", "startSyncExecution")
                    ? "states:StartSyncExecution" : "states:StartExecution";
            Optional<String> refusal = authorizer.refusal(roleArn, action, target);
            if (refusal.isPresent()) {
                throw accessDenied(integration, refusal.get());
            }
        }
        return role.accountId();
    }

    /**
     * Whether a task with {@code resource} makes its calls as its {@code Credentials} role, in that role's account: a
     * Lambda function or an AWS service integration, in any of its forms. Not an activity, nor an HTTP task, nor an
     * API Gateway task, which reaches only an API of the state machine's account and signs as its role itself.
     */
    static boolean routes(String resource) {
        StatesIntegration integration = StatesIntegration.parse(resource).orElse(null);
        if (integration == null) {
            return isLambdaFunctionArn(resource);
        }
        return !integration.isAnySuffix("http", "invoke") && !integration.isAnySuffix("apigateway", "invoke");
    }

    /**
     * The state machine's account for a {@code PutEvents} entry whose {@code EventBusName} is the ARN of a bus of that
     * account, which it reaches even when a {@code Credentials} role's account is the one the task's calls run in;
     * null for any other entry, which goes to the bus of the account the calls run in. The task's permission check
     * and its delivery both read it, so they reach the same bus.
     */
    static String putEventsAccount(JsonNode entry, String machineAccount) {
        String bus = entry.path("EventBusName").asText(null);
        return AwsArnUtils.isArnFor(bus, "events") && machineAccount.equals(AwsArnUtils.parse(bus).accountId())
                ? machineAccount : null;
    }

    /**
     * The machine as the account a task's calls run in sees it, so a resource the task names without its account is
     * the one of that account. The role and the rest stay the state machine's.
     */
    static StateMachine inAccount(StateMachine machine, String account) {
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        if (account == null || account.equals(owner.accountId())) {
            return machine;
        }
        StateMachine view = new StateMachine();
        view.setStateMachineArn(new AwsArnUtils.Arn(owner.partition(), owner.service(), owner.region(), account,
                owner.resource()).toString());
        view.setRoleArn(machine.getRoleArn());
        return view;
    }

    private static boolean isLambda(String resource, StatesIntegration integration) {
        return integration == null ? isLambdaFunctionArn(resource) : integration.isAnySuffix("lambda", "invoke");
    }

    private static boolean isLambdaFunctionArn(String resource) {
        return AwsArnUtils.isArnFor(resource, "lambda") && resource.contains(":function:");
    }

    static void validateShape(JsonNode credentials) {
        if (!credentials.isObject() || credentials.size() != 1 || !credentials.path("RoleArn").isTextual()) {
            throw new AslExecutor.FailStateException("States.Runtime",
                    "Task Credentials must resolve to an object containing RoleArn");
        }
    }

    static AslExecutor.FailStateException assumptionDenied(StateMachine machine, String roleArn) {
        return new AslExecutor.FailStateException("States.TaskFailed",
                "The role " + machine.getRoleArn() + " is not authorized to assume the task state's role, "
                        + roleArn + ".");
    }

    /**
     * Fails a nested execution whose state machine the task cannot reach: one of another account than the task's,
     * or one in another Region, since "Cross-Region AWS SDK integration and cross-Region AWS resource access aren't
     * available in Step Functions" (Developer Guide, "Accessing cross-account AWS resources in Step Functions"). AWS
     * names no error for the Region case, so both fail with the integration's access error.
     */
    static void validateTarget(String resource, JsonNode input, StateMachine machine, String taskAccount) {
        StatesIntegration integration = StatesIntegration.parse(resource).orElse(null);
        String target = input.path("StateMachineArn").asText(null);
        boolean nested = isNested(integration);
        // A callback task starts the child too, once the executor strips .waitForTaskToken.
        boolean callback = integration != null && integration.isAnySuffix("states", "startExecution")
                && ".waitForTaskToken".equals(integration.suffix());
        if (!(nested || callback) || !AwsArnUtils.isArn(target)) {
            return;
        }
        AwsArnUtils.Arn machineArn = AwsArnUtils.parse(machine.getStateMachineArn());
        AwsArnUtils.Arn targetArn = AwsArnUtils.parse(target);
        String account = taskAccount != null ? taskAccount : machineArn.accountId();
        if (nested && !account.equals(targetArn.accountId())) {
            throw accessDenied(integration, "Not authorized to access this resource: " + target);
        }
        if (!targetArn.region().isEmpty() && !targetArn.region().equals(machineArn.region())) {
            throw accessDenied(integration, "Cross-Region access is not available in Step Functions: "
                    + target + " is not in the execution's Region, " + machineArn.region());
        }
    }

    private static AslExecutor.FailStateException accessDenied(StatesIntegration integration, String cause) {
        String error = integration.isAnySuffix("states", "startExecution")
                ? "StepFunctions.AWSStepFunctionsException" : "Sfn.SfnException";
        return new AslExecutor.FailStateException(error, cause);
    }

    static boolean isNested(StatesIntegration integration) {
        return integration != null
                && ((integration.isAnySuffix("states", "startExecution")
                    && Set.of("", ".sync", ".sync:2").contains(integration.suffix()))
                    || integration.isSdk("sfn", "startExecution")
                    || integration.isSdk("sfn", "startSyncExecution"));
    }
}

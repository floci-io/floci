package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.Set;

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

    String account(JsonNode credentials, String resource, JsonNode input, StateMachine machine) {
        StatesIntegration integration = StatesIntegration.parse(resource).orElse(null);
        if (!isNested(integration)) {
            throw new AslExecutor.FailStateException("States.Runtime",
                    "Task Credentials account routing is supported only for nested executions");
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
        if (config.services().iam().enforcementEnabled()) {
            if (authorizer.assumeRefusal(machine.getRoleArn(), roleArn, machine.getStateMachineArn()).isPresent()) {
                throw assumptionDenied(machine, roleArn);
            }
            validateTargetAccount(resource, input, machine, role.accountId());
            String target = input.path("StateMachineArn").asText(null);
            if (AwsArnUtils.isArn(target)) {
                String action = integration.isSdk("sfn", "startSyncExecution")
                        ? "states:StartSyncExecution" : "states:StartExecution";
                Optional<String> refusal = authorizer.refusal(roleArn, action, target);
                if (refusal.isPresent()) {
                    throw accessDenied(integration, refusal.get());
                }
            }
        }
        return role.accountId();
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

    static void validateTargetAccount(String resource, JsonNode input, StateMachine machine, String taskAccount) {
        StatesIntegration integration = StatesIntegration.parse(resource).orElse(null);
        String target = input.path("StateMachineArn").asText(null);
        if (!isNested(integration) || !AwsArnUtils.isArn(target)) {
            return;
        }
        String account = taskAccount != null ? taskAccount : AwsArnUtils.parse(machine.getStateMachineArn()).accountId();
        if (!account.equals(AwsArnUtils.parse(target).accountId())) {
            throw accessDenied(integration, "Not authorized to access this resource: " + target);
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

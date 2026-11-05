package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.stepfunctions.LambdaTaskAuthorizer.Permission;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;

import java.util.List;
import java.util.Optional;

/**
 * What a task's call to its integrated service needs from IAM. A request that names no resource
 * needs no permission here: the integrated service refuses it with its own validation error.
 *
 * @param errorPrefix the integration's error prefix, dot included
 * @param permissions the actions the call performs
 */
record TaskPermissions(String errorPrefix, List<Permission> permissions) {

    /** The permissions of a mapped integration, or nothing for one that is not checked. */
    static Optional<TaskPermissions> of(StatesIntegration integration, JsonNode input, StateMachine machine) {
        if (integration.is("sqs", "sendMessage")) {
            return Optional.of(sqsSendMessage("SQS.", input, machine));
        }
        if (integration.isSdk("sqs", "sendMessage")) {
            return Optional.of(sqsSendMessage("Sqs.", input, machine));
        }
        return Optional.empty();
    }

    private static TaskPermissions sqsSendMessage(String errorPrefix, JsonNode input, StateMachine machine) {
        JsonNode queueUrl = input.path("QueueUrl");
        if (!queueUrl.isTextual() || queueUrl.asText().isBlank()) {
            return new TaskPermissions(errorPrefix, List.of());
        }
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        return new TaskPermissions(errorPrefix, List.of(new Permission("sqs:SendMessage",
                SqsService.queueArn(queueUrl.asText(), owner.partition(), owner.region(), owner.accountId()))));
    }
}

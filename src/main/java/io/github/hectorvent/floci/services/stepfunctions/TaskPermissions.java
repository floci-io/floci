package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.stepfunctions.LambdaTaskAuthorizer.Permission;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a task's call to its integrated service needs from IAM. A request that names no resource
 * needs no permission here: the integrated service refuses it with its own validation error.
 *
 * @param deniedError the error a refused permission fails the task with: the integration's prefix and
 *                    the exception the integrated service's SDK raises for an access denial
 * @param permissions the actions the call performs
 */
record TaskPermissions(String deniedError, List<Permission> permissions) {

    /** The permissions of a mapped integration, or nothing for one that is not checked. */
    static Optional<TaskPermissions> of(StatesIntegration integration, JsonNode input, StateMachine machine) {
        if (integration.is("sqs", "sendMessage")) {
            return Optional.of(sqsSendMessage("SQS.", input, machine));
        }
        if (integration.isSdk("sqs", "sendMessage")) {
            return Optional.of(sqsSendMessage("Sqs.", input, machine));
        }
        if (integration.is("sns", "publish")) {
            return Optional.of(snsPublish("SNS.", input));
        }
        if (integration.isSdk("sns", "publish")) {
            return Optional.of(snsPublish("Sns.", input));
        }
        if (integration.is("events", "putEvents")) {
            return Optional.of(eventsPutEvents(input, machine));
        }
        return Optional.empty();
    }

    private static TaskPermissions sqsSendMessage(String errorPrefix, JsonNode input, StateMachine machine) {
        JsonNode queueUrl = input.path("QueueUrl");
        if (!queueUrl.isTextual() || queueUrl.asText().isBlank()) {
            return new TaskPermissions(errorPrefix + "AccessDeniedException", List.of());
        }
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        return new TaskPermissions(errorPrefix + "AccessDeniedException", List.of(new Permission("sqs:SendMessage",
                SqsService.queueArn(queueUrl.asText(), owner.partition(), owner.region(), owner.accountId()))));
    }

    /**
     * What {@code Publish} sends to, read as its handler reads the members and in the order
     * {@code SnsService.publish} takes them: a phone number before a topic, and a topic before a target.
     * Sending an SMS has no resource to check. A value that is not an SNS ARN reaches nothing, as the
     * lookups need the ARN a topic or an endpoint is stored under, so SNS answers it. A refusal is SNS's
     * {@code AuthorizationError}, which its SDKs raise as {@code AuthorizationErrorException}.
     */
    private static TaskPermissions snsPublish(String errorPrefix, JsonNode input) {
        String deniedError = errorPrefix + "AuthorizationErrorException";
        if (input.path("PhoneNumber").asText(null) != null) {
            return new TaskPermissions(deniedError, List.of(new Permission("sns:Publish", "*")));
        }
        String topicArn = input.path("TopicArn").asText(null);
        String resourceArn = topicArn != null ? topicArn : input.path("TargetArn").asText(null);
        if (!AwsArnUtils.isArnFor(resourceArn, "sns")) {
            return new TaskPermissions(deniedError, List.of());
        }
        return new TaskPermissions(deniedError, List.of(new Permission("sns:Publish", resourceArn)));
    }

    /**
     * One permission for each distinct bus the entries reach, read as {@code PutEvents}' handler reads
     * the member: an entry naming none goes to the default bus. An entry that is not an object or whose
     * member is a malformed ARN reaches no bus, so EventBridge answers it.
     */
    private static TaskPermissions eventsPutEvents(JsonNode input, StateMachine machine) {
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        Set<String> buses = new LinkedHashSet<>();
        JsonNode entries = input.path("Entries");
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                if (entry.isObject()) {
                    EventBridgeService.eventBusArn(entry.path("EventBusName").asText(null), owner.partition(),
                            owner.region(), owner.accountId()).ifPresent(buses::add);
                }
            }
        }
        return new TaskPermissions("EventBridge.AccessDeniedException",
                buses.stream().map(bus -> new Permission("events:PutEvents", bus)).toList());
    }
}

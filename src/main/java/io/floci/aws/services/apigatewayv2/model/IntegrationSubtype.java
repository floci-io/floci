package io.floci.aws.services.apigatewayv2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An HTTP API {@code AWS_PROXY} integration that calls an AWS service action directly instead of
 * a Lambda function. The request parameters name the action's input members: {@code required}
 * must all be mapped, {@code optional} may be, and nothing else is accepted. {@code Region} is
 * accepted by every subtype and selects the region the action runs in.
 *
 * <p>Every subtype AWS defines is known, so each is validated when an integration is created or
 * updated. Only a subtype with a {@link #service()} is invoked; the others are stored and answer
 * an error when a request reaches them.
 */
@RegisterForReflection
public enum IntegrationSubtype {
    EVENTBRIDGE_PUT_EVENTS("EventBridge-PutEvents",
            List.of("Detail", "DetailType", "Source"),
            List.of("Time", "EventBusName", "Resources", "Region", "TraceHeader")),
    SQS_SEND_MESSAGE("SQS-SendMessage", "sqs", "SendMessage",
            List.of("QueueUrl", "MessageBody"),
            List.of("DelaySeconds", "MessageAttributes", "MessageDeduplicationId", "MessageGroupId",
                    "MessageSystemAttributes", "Region"),
            Set.of("DelaySeconds"),
            Set.of("MessageAttributes", "MessageSystemAttributes"),
            List.of("MD5OfMessageAttributes", "MD5OfMessageBody", "MD5OfMessageSystemAttributes",
                    "MessageId", "SequenceNumber")),
    SQS_RECEIVE_MESSAGE("SQS-ReceiveMessage",
            List.of("QueueUrl"),
            List.of("AttributeNames", "MaxNumberOfMessages", "MessageAttributeNames", "ReceiveRequestAttemptId",
                    "VisibilityTimeout", "WaitTimeSeconds", "Region")),
    SQS_DELETE_MESSAGE("SQS-DeleteMessage",
            List.of("ReceiptHandle", "QueueUrl"),
            List.of("Region")),
    SQS_PURGE_QUEUE("SQS-PurgeQueue",
            List.of("QueueUrl"),
            List.of("Region")),
    APPCONFIG_GET_CONFIGURATION("AppConfig-GetConfiguration",
            List.of("Application", "Environment", "Configuration", "ClientId"),
            List.of("ClientConfigurationVersion", "Region")),
    KINESIS_PUT_RECORD("Kinesis-PutRecord",
            List.of("StreamName", "Data", "PartitionKey"),
            List.of("SequenceNumberForOrdering", "ExplicitHashKey", "Region")),
    STEPFUNCTIONS_START_EXECUTION("StepFunctions-StartExecution",
            List.of("StateMachineArn"),
            List.of("Name", "Input", "Region")),
    STEPFUNCTIONS_START_SYNC_EXECUTION("StepFunctions-StartSyncExecution",
            List.of("StateMachineArn"),
            List.of("Name", "Input", "Region", "TraceHeader")),
    STEPFUNCTIONS_STOP_EXECUTION("StepFunctions-StopExecution",
            List.of("ExecutionArn"),
            List.of("Cause", "Error", "Region"));

    public static final String REGION_PARAMETER = "Region";

    private final String subtypeName;
    private final String service;
    private final String action;
    private final List<String> requiredParameters;
    private final List<String> optionalParameters;
    private final Set<String> integerParameters;
    private final Set<String> jsonParameters;
    private final List<String> resultMembers;

    IntegrationSubtype(String subtypeName, List<String> requiredParameters, List<String> optionalParameters) {
        this(subtypeName, null, null, requiredParameters, optionalParameters, Set.of(), Set.of(), List.of());
    }

    IntegrationSubtype(String subtypeName, String service, String action, List<String> requiredParameters,
                       List<String> optionalParameters, Set<String> integerParameters,
                       Set<String> jsonParameters, List<String> resultMembers) {
        this.subtypeName = subtypeName;
        this.service = service;
        this.action = action;
        this.requiredParameters = requiredParameters;
        this.optionalParameters = optionalParameters;
        this.integerParameters = integerParameters;
        this.jsonParameters = jsonParameters;
        this.resultMembers = resultMembers;
    }

    public static Optional<IntegrationSubtype> fromName(String name) {
        for (IntegrationSubtype subtype : values()) {
            if (subtype.subtypeName.equals(name)) {
                return Optional.of(subtype);
            }
        }
        return Optional.empty();
    }

    public boolean accepts(String parameter) {
        return requiredParameters.contains(parameter) || optionalParameters.contains(parameter);
    }

    public boolean isInvocable() { return service != null; }
    public String subtypeName() { return subtypeName; }
    public String service() { return service; }
    public String action() { return action; }
    public List<String> requiredParameters() { return requiredParameters; }
    public List<String> optionalParameters() { return optionalParameters; }
    public boolean isIntegerParameter(String parameter) { return integerParameters.contains(parameter); }
    public boolean isJsonParameter(String parameter) { return jsonParameters.contains(parameter); }
    public List<String> resultMembers() { return resultMembers; }
}

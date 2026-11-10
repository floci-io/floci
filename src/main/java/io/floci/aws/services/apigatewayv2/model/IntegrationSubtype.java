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
 */
@RegisterForReflection
public enum IntegrationSubtype {
    SQS_SEND_MESSAGE("SQS-SendMessage", "sqs", "SendMessage",
            List.of("QueueUrl", "MessageBody"),
            List.of("DelaySeconds", "MessageAttributes", "MessageDeduplicationId", "MessageGroupId",
                    "MessageSystemAttributes", "Region"),
            Set.of("DelaySeconds"),
            Set.of("MessageAttributes", "MessageSystemAttributes"),
            List.of("MD5OfMessageAttributes", "MD5OfMessageBody", "MD5OfMessageSystemAttributes",
                    "MessageId", "SequenceNumber"));

    public static final String REGION_PARAMETER = "Region";

    private final String subtypeName;
    private final String service;
    private final String action;
    private final List<String> requiredParameters;
    private final List<String> optionalParameters;
    private final Set<String> integerParameters;
    private final Set<String> jsonParameters;
    private final List<String> resultMembers;

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

    public String subtypeName() { return subtypeName; }
    public String service() { return service; }
    public String action() { return action; }
    public List<String> requiredParameters() { return requiredParameters; }
    public List<String> optionalParameters() { return optionalParameters; }
    public boolean isIntegerParameter(String parameter) { return integerParameters.contains(parameter); }
    public boolean isJsonParameter(String parameter) { return jsonParameters.contains(parameter); }
    public List<String> resultMembers() { return resultMembers; }
}

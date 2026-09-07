package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One action inside a receipt rule, stored as its wire type name plus the members that were
 * actually provided. SES renders only the members a caller set (verified against real AWS:
 * an S3Action created with just BucketName and ObjectKeyPrefix describes back exactly those
 * two members), so a typed class per action would have to model absent-vs-set anyway; a
 * single ordered property map keeps parse, storage, and render symmetric.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReceiptAction {

    /**
     * Wire member order per action type, used both to parse the Query request and to render
     * the Describe XML. A rule may also carry a completely empty action member with no type
     * at all; real SES accepts and stores it, so {@link #type} may be null.
     *
     * <p>WorkmailAction is kept for wire compatibility only: Amazon WorkMail reaches end of
     * support on 2027-03-31, so the action gets storage and round-trip (matching real SES, which
     * accepts even a fabricated organization ARN) but will never get resource validation or
     * execution behavior.
     */
    public static final Map<String, List<String>> ACTION_FIELDS = Map.of(
            "AddHeaderAction", List.of("HeaderName", "HeaderValue"),
            "BounceAction", List.of("TopicArn", "SmtpReplyCode", "StatusCode", "Message", "Sender"),
            "ConnectAction", List.of("InstanceARN", "IAMRoleARN"),
            "LambdaAction", List.of("TopicArn", "FunctionArn", "InvocationType"),
            "S3Action", List.of("TopicArn", "BucketName", "ObjectKeyPrefix", "KmsKeyArn", "IamRoleArn"),
            "SNSAction", List.of("TopicArn", "Encoding"),
            "StopAction", List.of("Scope", "TopicArn"),
            "WorkmailAction", List.of("TopicArn", "OrganizationArn"));

    @JsonProperty("Type")
    private String type;

    @JsonProperty("Properties")
    private Map<String, String> properties = new LinkedHashMap<>();

    public ReceiptAction() {
    }

    public ReceiptAction(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, String> getProperties() {
        if (properties == null) {
            properties = new LinkedHashMap<>();
        }
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    public String property(String name) {
        return getProperties().get(name);
    }

    public boolean is(String actionType) {
        return actionType.equals(type);
    }
}

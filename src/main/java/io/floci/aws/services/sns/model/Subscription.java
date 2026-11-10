package io.floci.aws.services.sns.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Subscription {

    @JsonProperty("SubscriptionArn")
    private String subscriptionArn;

    @JsonProperty("TopicArn")
    private String topicArn;

    @JsonProperty("Protocol")
    private String protocol;

    @JsonProperty("Endpoint")
    private String endpoint;

    @JsonProperty("Owner")
    private String owner;

    private String accountId;

    @JsonProperty("Attributes")
    private Map<String, String> attributes = new HashMap<>();

    public Subscription() {}

    public Subscription(String subscriptionArn, String topicArn, String protocol, String endpoint, String owner) {
        this.subscriptionArn = subscriptionArn;
        this.topicArn = topicArn;
        this.protocol = protocol;
        this.endpoint = endpoint;
        this.owner = owner;
    }

    public String getSubscriptionArn() { return subscriptionArn; }
    public void setSubscriptionArn(String subscriptionArn) { this.subscriptionArn = subscriptionArn; }

    public String getTopicArn() { return topicArn; }
    public void setTopicArn(String topicArn) { this.topicArn = topicArn; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
}

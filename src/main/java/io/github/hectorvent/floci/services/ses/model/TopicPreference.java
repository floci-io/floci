package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A contact's subscription preference for a single SES V2 contact-list topic:
 * a topic name and a subscription status ({@code OPT_IN} or {@code OPT_OUT}).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TopicPreference {

    @JsonProperty("TopicName")
    private String topicName;

    @JsonProperty("SubscriptionStatus")
    private String subscriptionStatus;

    public TopicPreference() {}

    public TopicPreference(String topicName, String subscriptionStatus) {
        this.topicName = topicName;
        this.subscriptionStatus = subscriptionStatus;
    }

    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }

    public String getSubscriptionStatus() { return subscriptionStatus; }
    public void setSubscriptionStatus(String subscriptionStatus) {
        this.subscriptionStatus = subscriptionStatus;
    }
}

package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A subscription topic within a SES V2 contact list. Mirrors the AWS {@code Topic}
 * shape: a topic name, its display name, the default subscription status
 * ({@code OPT_IN} or {@code OPT_OUT}), and an optional description.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Topic {

    @JsonProperty("TopicName")
    private String topicName;

    @JsonProperty("DisplayName")
    private String displayName;

    @JsonProperty("DefaultSubscriptionStatus")
    private String defaultSubscriptionStatus;

    @JsonProperty("Description")
    private String description;

    public Topic() {}

    public Topic(String topicName, String displayName, String defaultSubscriptionStatus,
                 String description) {
        this.topicName = topicName;
        this.displayName = displayName;
        this.defaultSubscriptionStatus = defaultSubscriptionStatus;
        this.description = description;
    }

    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDefaultSubscriptionStatus() { return defaultSubscriptionStatus; }
    public void setDefaultSubscriptionStatus(String defaultSubscriptionStatus) {
        this.defaultSubscriptionStatus = defaultSubscriptionStatus;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}

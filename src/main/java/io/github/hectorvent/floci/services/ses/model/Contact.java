package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A contact within an SES V2 contact list: an email address plus its explicit
 * per-topic subscription preferences, an unsubscribe-all flag, and free-form
 * attributes. {@code TopicDefaultPreferences} is not stored — it is derived at
 * read time from the list's topics for topics the contact hasn't set explicitly.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Contact {

    @JsonProperty("EmailAddress")
    private String emailAddress;

    @JsonProperty("TopicPreferences")
    private List<TopicPreference> topicPreferences = new ArrayList<>();

    @JsonProperty("UnsubscribeAll")
    private boolean unsubscribeAll;

    @JsonProperty("AttributesData")
    private String attributesData;

    @JsonProperty("CreatedTimestamp")
    private Instant createdTimestamp;

    @JsonProperty("LastUpdatedTimestamp")
    private Instant lastUpdatedTimestamp;

    public Contact() {}

    public Contact(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    public String getEmailAddress() { return emailAddress; }
    public void setEmailAddress(String emailAddress) { this.emailAddress = emailAddress; }

    public List<TopicPreference> getTopicPreferences() { return topicPreferences; }
    public void setTopicPreferences(List<TopicPreference> topicPreferences) {
        this.topicPreferences = topicPreferences == null ? new ArrayList<>() : topicPreferences;
    }

    public boolean isUnsubscribeAll() { return unsubscribeAll; }
    public void setUnsubscribeAll(boolean unsubscribeAll) { this.unsubscribeAll = unsubscribeAll; }

    public String getAttributesData() { return attributesData; }
    public void setAttributesData(String attributesData) { this.attributesData = attributesData; }

    public Instant getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(Instant createdTimestamp) { this.createdTimestamp = createdTimestamp; }

    public Instant getLastUpdatedTimestamp() { return lastUpdatedTimestamp; }
    public void setLastUpdatedTimestamp(Instant lastUpdatedTimestamp) {
        this.lastUpdatedTimestamp = lastUpdatedTimestamp;
    }
}

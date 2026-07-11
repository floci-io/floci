package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A SES V2 contact list: a named container of subscription topics. AWS allows at
 * most one contact list per account per region.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContactList {

    @JsonProperty("ContactListName")
    private String contactListName;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("Topics")
    private List<Topic> topics = new ArrayList<>();

    @JsonProperty("Tags")
    private List<Tag> tags = new ArrayList<>();

    @JsonProperty("CreatedTimestamp")
    private Instant createdTimestamp;

    @JsonProperty("LastUpdatedTimestamp")
    private Instant lastUpdatedTimestamp;

    public ContactList() {}

    public ContactList(String contactListName) {
        this.contactListName = contactListName;
    }

    public String getContactListName() { return contactListName; }
    public void setContactListName(String contactListName) { this.contactListName = contactListName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<Topic> getTopics() { return topics; }
    public void setTopics(List<Topic> topics) {
        this.topics = topics == null ? new ArrayList<>() : topics;
    }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) {
        this.tags = tags == null ? new ArrayList<>() : tags;
    }

    public Instant getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(Instant createdTimestamp) { this.createdTimestamp = createdTimestamp; }

    public Instant getLastUpdatedTimestamp() { return lastUpdatedTimestamp; }
    public void setLastUpdatedTimestamp(Instant lastUpdatedTimestamp) {
        this.lastUpdatedTimestamp = lastUpdatedTimestamp;
    }
}

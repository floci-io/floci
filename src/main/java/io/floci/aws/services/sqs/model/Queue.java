package io.floci.aws.services.sqs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Queue {

    private String queueName;
    private String queueUrl;
    private String accountId;
    private Map<String, String> attributes;
    private Map<String, String> tags;
    private Instant createdTimestamp;
    private Instant lastModifiedTimestamp;

    public Queue() {
        this.attributes = new HashMap<>();
        this.tags = new HashMap<>();
    }

    public Queue(String queueName, String queueUrl) {
        this.queueName = queueName;
        this.queueUrl = queueUrl;
        this.attributes = new HashMap<>();
        this.tags = new HashMap<>();
        this.createdTimestamp = Instant.now();
        this.lastModifiedTimestamp = Instant.now();
    }

    public String getQueueName() { return queueName; }
    public void setQueueName(String queueName) { this.queueName = queueName; }

    public String getQueueUrl() { return queueUrl; }
    public void setQueueUrl(String queueUrl) { this.queueUrl = queueUrl; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Instant getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(Instant createdTimestamp) { this.createdTimestamp = createdTimestamp; }

    public Instant getLastModifiedTimestamp() { return lastModifiedTimestamp; }
    public void setLastModifiedTimestamp(Instant lastModifiedTimestamp) { this.lastModifiedTimestamp = lastModifiedTimestamp; }

    @JsonIgnore
    public boolean isFifo() {
        return queueName != null && queueName.endsWith(".fifo");
    }
}

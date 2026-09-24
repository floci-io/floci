package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An RDS event notification subscription, as DescribeEventSubscriptions reports it. */
@RegisterForReflection
public class EventSubscription {

    private String customerAwsId;
    private String custSubscriptionId;
    private String snsTopicArn;
    private String status;
    private String subscriptionCreationTime;
    private String sourceType;
    private List<String> sourceIdsList = new ArrayList<>();
    private List<String> eventCategoriesList = new ArrayList<>();
    private boolean enabled;
    private String eventSubscriptionArn;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getCustomerAwsId() { return customerAwsId; }
    public void setCustomerAwsId(String customerAwsId) { this.customerAwsId = customerAwsId; }
    public String getCustSubscriptionId() { return custSubscriptionId; }
    public void setCustSubscriptionId(String custSubscriptionId) { this.custSubscriptionId = custSubscriptionId; }
    public String getSnsTopicArn() { return snsTopicArn; }
    public void setSnsTopicArn(String snsTopicArn) { this.snsTopicArn = snsTopicArn; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSubscriptionCreationTime() { return subscriptionCreationTime; }
    public void setSubscriptionCreationTime(String subscriptionCreationTime) {
        this.subscriptionCreationTime = subscriptionCreationTime;
    }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public List<String> getSourceIdsList() { return sourceIdsList; }
    public void setSourceIdsList(List<String> sourceIdsList) { this.sourceIdsList = sourceIdsList; }
    public List<String> getEventCategoriesList() { return eventCategoriesList; }
    public void setEventCategoriesList(List<String> eventCategoriesList) {
        this.eventCategoriesList = eventCategoriesList;
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public String getEventSubscriptionArn() { return eventSubscriptionArn; }
    public void setEventSubscriptionArn(String eventSubscriptionArn) {
        this.eventSubscriptionArn = eventSubscriptionArn;
    }
}

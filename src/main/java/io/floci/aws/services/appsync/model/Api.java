package io.floci.aws.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * An AppSync Events API, the {@code /v2/apis} resource. {@code created} is epoch seconds, the
 * restJson1 default timestamp format, which the model does not override.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Api {
    private String apiId;
    private String name;
    private String ownerContact;
    private Map<String, String> tags;
    private Map<String, String> dns;
    private String apiArn;
    private Double created;
    private Boolean xrayEnabled;
    private String wafWebAclArn;
    private EventConfig eventConfig;

    public String getApiId() {
        return apiId;
    }

    public void setApiId(String apiId) {
        this.apiId = apiId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOwnerContact() {
        return ownerContact;
    }

    public void setOwnerContact(String ownerContact) {
        this.ownerContact = ownerContact;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public Map<String, String> getDns() {
        return dns;
    }

    public void setDns(Map<String, String> dns) {
        this.dns = dns;
    }

    public String getApiArn() {
        return apiArn;
    }

    public void setApiArn(String apiArn) {
        this.apiArn = apiArn;
    }

    public Double getCreated() {
        return created;
    }

    public void setCreated(Double created) {
        this.created = created;
    }

    public Boolean getXrayEnabled() {
        return xrayEnabled;
    }

    public void setXrayEnabled(Boolean xrayEnabled) {
        this.xrayEnabled = xrayEnabled;
    }

    public String getWafWebAclArn() {
        return wafWebAclArn;
    }

    public void setWafWebAclArn(String wafWebAclArn) {
        this.wafWebAclArn = wafWebAclArn;
    }

    public EventConfig getEventConfig() {
        return eventConfig;
    }

    public void setEventConfig(EventConfig eventConfig) {
        this.eventConfig = eventConfig;
    }
}

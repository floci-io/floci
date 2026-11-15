package io.floci.aws.services.xray.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A sampling rule as X-Ray stores it: the match criteria, the rate the daemon samples at and the
 * reservoir it may borrow from.
 *
 * <p>The rule AWS always serves, {@code Default}, is not stored here: it is synthesised per Region
 * so deleting it and never creating it both answer the same rule.
 */
@RegisterForReflection
public class SamplingRule {

    private String ruleName;
    private String ruleArn;
    private String resourceArn;
    private Integer priority;
    private Double fixedRate;
    private Integer reservoirSize;
    private String serviceName;
    private String serviceType;
    private String host;
    private String httpMethod;
    private String urlPath;
    private Integer version;
    private Map<String, String> attributes = new LinkedHashMap<>();
    private long createdAt;
    private long modifiedAt;

    /**
     * A copy, so an update validates against a value that is not the stored one: a rejected update
     * must leave the rule exactly as it was, and in memory mode the stored object is a live reference.
     */
    public SamplingRule copy() {
        SamplingRule copy = new SamplingRule();
        copy.ruleName = ruleName;
        copy.ruleArn = ruleArn;
        copy.resourceArn = resourceArn;
        copy.priority = priority;
        copy.fixedRate = fixedRate;
        copy.reservoirSize = reservoirSize;
        copy.serviceName = serviceName;
        copy.serviceType = serviceType;
        copy.host = host;
        copy.httpMethod = httpMethod;
        copy.urlPath = urlPath;
        copy.version = version;
        copy.attributes = new LinkedHashMap<>(attributes);
        copy.createdAt = createdAt;
        copy.modifiedAt = modifiedAt;
        return copy;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getRuleArn() {
        return ruleArn;
    }

    public void setRuleArn(String ruleArn) {
        this.ruleArn = ruleArn;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public void setResourceArn(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Double getFixedRate() {
        return fixedRate;
    }

    public void setFixedRate(Double fixedRate) {
        this.fixedRate = fixedRate;
    }

    public Integer getReservoirSize() {
        return reservoirSize;
    }

    public void setReservoirSize(Integer reservoirSize) {
        this.reservoirSize = reservoirSize;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getUrlPath() {
        return urlPath;
    }

    public void setUrlPath(String urlPath) {
        this.urlPath = urlPath;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes == null ? new LinkedHashMap<>() : attributes;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(long modifiedAt) {
        this.modifiedAt = modifiedAt;
    }
}

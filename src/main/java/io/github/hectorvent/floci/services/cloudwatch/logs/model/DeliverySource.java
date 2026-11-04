package io.github.hectorvent.floci.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A vended-logs delivery source: an AWS resource that sends one type of log. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeliverySource {
    private String name;
    private String arn;
    private List<String> resourceArns = new ArrayList<>();
    private String service;
    private String logType;
    private Map<String, String> deliverySourceConfiguration = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public List<String> getResourceArns() { return resourceArns; }
    public void setResourceArns(List<String> resourceArns) {
        this.resourceArns = resourceArns != null ? resourceArns : new ArrayList<>();
    }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getLogType() { return logType; }
    public void setLogType(String logType) { this.logType = logType; }
    public Map<String, String> getDeliverySourceConfiguration() { return deliverySourceConfiguration; }
    public void setDeliverySourceConfiguration(Map<String, String> configuration) {
        this.deliverySourceConfiguration = configuration != null ? configuration : new LinkedHashMap<>();
    }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new LinkedHashMap<>(); }
}

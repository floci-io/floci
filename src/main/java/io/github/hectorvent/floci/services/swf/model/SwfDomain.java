package io.github.hectorvent.floci.services.swf.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SwfDomain {

    private String name;
    private String arn;
    private String description;
    private String status = SwfConstants.STATUS_REGISTERED;
    private String workflowExecutionRetentionPeriodInDays;
    private double creationDate;
    private Double deprecationDate;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getWorkflowExecutionRetentionPeriodInDays() {
        return workflowExecutionRetentionPeriodInDays;
    }

    public void setWorkflowExecutionRetentionPeriodInDays(String workflowExecutionRetentionPeriodInDays) {
        this.workflowExecutionRetentionPeriodInDays = workflowExecutionRetentionPeriodInDays;
    }

    public double getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(double creationDate) {
        this.creationDate = creationDate;
    }

    public Double getDeprecationDate() {
        return deprecationDate;
    }

    public void setDeprecationDate(Double deprecationDate) {
        this.deprecationDate = deprecationDate;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public boolean isDeprecated() {
        return SwfConstants.STATUS_DEPRECATED.equals(status);
    }
}

package io.github.hectorvent.floci.services.fis.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExperimentTemplate {
    private String id;
    private String arn;
    private String description;
    private Map<String, ExperimentTemplateTarget> targets;
    private Map<String, ExperimentTemplateAction> actions;
    private List<StopCondition> stopConditions;
    private double creationTime;
    private double lastUpdateTime;
    private String roleArn;
    private Map<String, String> tags;
    private JsonNode logConfiguration;
    private ExperimentTemplateOptions experimentOptions;
    private long targetAccountConfigurationsCount;
    private JsonNode experimentReportConfiguration;

    public ExperimentTemplate() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public Map<String, ExperimentTemplateTarget> getTargets() {
        return targets;
    }

    public void setTargets(Map<String, ExperimentTemplateTarget> targets) {
        this.targets = targets;
    }

    public Map<String, ExperimentTemplateAction> getActions() {
        return actions;
    }

    public void setActions(Map<String, ExperimentTemplateAction> actions) {
        this.actions = actions;
    }

    public List<StopCondition> getStopConditions() {
        return stopConditions;
    }

    public void setStopConditions(List<StopCondition> stopConditions) {
        this.stopConditions = stopConditions;
    }

    public double getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(double creationTime) {
        this.creationTime = creationTime;
    }

    public double getLastUpdateTime() {
        return lastUpdateTime;
    }

    public void setLastUpdateTime(double lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public JsonNode getLogConfiguration() {
        return logConfiguration;
    }

    public void setLogConfiguration(JsonNode logConfiguration) {
        this.logConfiguration = logConfiguration;
    }

    public ExperimentTemplateOptions getExperimentOptions() {
        return experimentOptions;
    }

    public void setExperimentOptions(ExperimentTemplateOptions experimentOptions) {
        this.experimentOptions = experimentOptions;
    }

    public long getTargetAccountConfigurationsCount() {
        return targetAccountConfigurationsCount;
    }

    public void setTargetAccountConfigurationsCount(long targetAccountConfigurationsCount) {
        this.targetAccountConfigurationsCount = targetAccountConfigurationsCount;
    }

    public JsonNode getExperimentReportConfiguration() {
        return experimentReportConfiguration;
    }

    public void setExperimentReportConfiguration(JsonNode experimentReportConfiguration) {
        this.experimentReportConfiguration = experimentReportConfiguration;
    }
}

package io.github.hectorvent.floci.services.fis.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Experiment {
    private String id;
    private String arn;
    private String experimentTemplateId;
    private String roleArn;
    private ExperimentState state;
    private Map<String, ExperimentTarget> targets;
    private Map<String, ExperimentAction> actions;
    private List<StopCondition> stopConditions;
    private double creationTime;
    private Double startTime;
    private Double endTime;
    private Map<String, String> tags;
    private JsonNode logConfiguration;
    private ExperimentOptions experimentOptions;
    private long targetAccountConfigurationsCount;
    private JsonNode experimentReportConfiguration;
    private JsonNode experimentReport;

    public Experiment() {
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

    public String getExperimentTemplateId() {
        return experimentTemplateId;
    }

    public void setExperimentTemplateId(String experimentTemplateId) {
        this.experimentTemplateId = experimentTemplateId;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public ExperimentState getState() {
        return state;
    }

    public void setState(ExperimentState state) {
        this.state = state;
    }

    public Map<String, ExperimentTarget> getTargets() {
        return targets;
    }

    public void setTargets(Map<String, ExperimentTarget> targets) {
        this.targets = targets;
    }

    public Map<String, ExperimentAction> getActions() {
        return actions;
    }

    public void setActions(Map<String, ExperimentAction> actions) {
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

    public Double getStartTime() {
        return startTime;
    }

    public void setStartTime(Double startTime) {
        this.startTime = startTime;
    }

    public Double getEndTime() {
        return endTime;
    }

    public void setEndTime(Double endTime) {
        this.endTime = endTime;
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

    public ExperimentOptions getExperimentOptions() {
        return experimentOptions;
    }

    public void setExperimentOptions(ExperimentOptions experimentOptions) {
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

    public JsonNode getExperimentReport() {
        return experimentReport;
    }

    public void setExperimentReport(JsonNode experimentReport) {
        this.experimentReport = experimentReport;
    }
}

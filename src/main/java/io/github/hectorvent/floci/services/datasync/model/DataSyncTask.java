package io.github.hectorvent.floci.services.datasync.model;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class DataSyncTask implements DataSyncTaggable {

    private String taskArn;
    private String name;
    private String status;
    private String taskMode;
    private String sourceLocationArn;
    private String destinationLocationArn;
    private String cloudWatchLogGroupArn;
    private JsonNode options;
    private JsonNode excludes;
    private JsonNode includes;
    private JsonNode schedule;
    private JsonNode manifestConfig;
    private JsonNode taskReportConfig;
    private Instant creationTime;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getTaskArn() {
        return taskArn;
    }

    public void setTaskArn(String taskArn) {
        this.taskArn = taskArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTaskMode() {
        return taskMode;
    }

    public void setTaskMode(String taskMode) {
        this.taskMode = taskMode;
    }

    public String getSourceLocationArn() {
        return sourceLocationArn;
    }

    public void setSourceLocationArn(String sourceLocationArn) {
        this.sourceLocationArn = sourceLocationArn;
    }

    public String getDestinationLocationArn() {
        return destinationLocationArn;
    }

    public void setDestinationLocationArn(String destinationLocationArn) {
        this.destinationLocationArn = destinationLocationArn;
    }

    public String getCloudWatchLogGroupArn() {
        return cloudWatchLogGroupArn;
    }

    public void setCloudWatchLogGroupArn(String cloudWatchLogGroupArn) {
        this.cloudWatchLogGroupArn = cloudWatchLogGroupArn;
    }

    public JsonNode getOptions() {
        return options;
    }

    public void setOptions(JsonNode options) {
        this.options = options;
    }

    public JsonNode getExcludes() {
        return excludes;
    }

    public void setExcludes(JsonNode excludes) {
        this.excludes = excludes;
    }

    public JsonNode getIncludes() {
        return includes;
    }

    public void setIncludes(JsonNode includes) {
        this.includes = includes;
    }

    public JsonNode getSchedule() {
        return schedule;
    }

    public void setSchedule(JsonNode schedule) {
        this.schedule = schedule;
    }

    public JsonNode getManifestConfig() {
        return manifestConfig;
    }

    public void setManifestConfig(JsonNode manifestConfig) {
        this.manifestConfig = manifestConfig;
    }

    public JsonNode getTaskReportConfig() {
        return taskReportConfig;
    }

    public void setTaskReportConfig(JsonNode taskReportConfig) {
        this.taskReportConfig = taskReportConfig;
    }

    public Instant getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(Instant creationTime) {
        this.creationTime = creationTime;
    }

    @Override
    public Map<String, String> getTags() {
        if (tags == null) {
            tags = new LinkedHashMap<>();
        }
        return tags;
    }

    @Override
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}

package io.github.hectorvent.floci.services.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventSourceMapping {

    private String uuid;
    private String functionArn;
    private String functionName;
    private String eventSourceArn;
    private String queueUrl;
    private String region;
    private boolean enabled = true;
    private int batchSize = 10;
    private String state = "Enabled";
    private long lastModified;
    private List<String> functionResponseTypes = new ArrayList<>();
    private Map<String, String> shardSequenceNumbers = new HashMap<>();

    public EventSourceMapping() {
    }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getFunctionArn() { return functionArn; }
    public void setFunctionArn(String functionArn) { this.functionArn = functionArn; }

    public String getFunctionName() { return functionName; }
    public void setFunctionName(String functionName) { this.functionName = functionName; }

    public String getEventSourceArn() { return eventSourceArn; }
    public void setEventSourceArn(String eventSourceArn) { this.eventSourceArn = eventSourceArn; }

    public String getQueueUrl() { return queueUrl; }
    public void setQueueUrl(String queueUrl) { this.queueUrl = queueUrl; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }

    public List<String> getFunctionResponseTypes() { return functionResponseTypes; }
    public void setFunctionResponseTypes(List<String> functionResponseTypes) {
        this.functionResponseTypes = functionResponseTypes != null ? functionResponseTypes : new ArrayList<>();
    }

    public boolean isReportBatchItemFailures() {
        return functionResponseTypes != null && functionResponseTypes.contains("ReportBatchItemFailures");
    }

    public Map<String, String> getShardSequenceNumbers() { return shardSequenceNumbers; }
    public void setShardSequenceNumbers(Map<String, String> shardSequenceNumbers) {
        this.shardSequenceNumbers = shardSequenceNumbers != null ? shardSequenceNumbers : new java.util.HashMap<>();
    }
}

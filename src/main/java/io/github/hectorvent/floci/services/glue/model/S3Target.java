package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class S3Target {
    @JsonProperty("ConnectionName")
    private String connectionName;

    @JsonProperty("DlqEventQueueArn")
    private String dlqEventQueueArn;

    @JsonProperty("EventQueueArn")
    private String eventQueueArn;

    @JsonProperty("Exclusions")
    private List<String> exclusions;

    @JsonProperty("Path")
    private String path;

    @JsonProperty("SampleSize")
    private Integer sampleSize;

    public S3Target() {}

    public String getConnectionName() { return connectionName; }
    public void setConnectionName(String connectionName) { this.connectionName = connectionName; }

    public String getDlqEventQueueArn() { return dlqEventQueueArn; }
    public void setDlqEventQueueArn(String dlqEventQueueArn) { this.dlqEventQueueArn = dlqEventQueueArn; }

    public String getEventQueueArn() { return eventQueueArn; }
    public void setEventQueueArn(String eventQueueArn) { this.eventQueueArn = eventQueueArn; }

    public List<String> getExclusions() { return exclusions; }
    public void setExclusions(List<String> exclusions) { this.exclusions = exclusions; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Integer getSampleSize() { return sampleSize; }
    public void setSampleSize(Integer sampleSize) { this.sampleSize = sampleSize; }
}

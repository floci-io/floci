package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class DynamoDBTarget {
    @JsonProperty("Path")
    private String path;

    @JsonProperty("ScanAll")
    private Boolean scanAll;

    @JsonProperty("ScanRate")
    private Double scanRate;

    public DynamoDBTarget() {}

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Boolean getScanAll() { return scanAll; }
    public void setScanAll(Boolean scanAll) { this.scanAll = scanAll; }

    public Double getScanRate() { return scanRate; }
    public void setScanRate(Double scanRate) { this.scanRate = scanRate; }
}

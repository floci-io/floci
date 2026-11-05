package io.github.hectorvent.floci.services.opensearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClusterConfig {

    @JsonProperty("InstanceType")
    private String instanceType = "m5.large.search";

    @JsonProperty("InstanceCount")
    private Integer instanceCount = 1;

    @JsonProperty("DedicatedMasterEnabled")
    private Boolean dedicatedMasterEnabled = false;

    @JsonProperty("ZoneAwarenessEnabled")
    private Boolean zoneAwarenessEnabled = false;

    /** {@code ZoneAwarenessConfig.AvailabilityZoneCount}; null when the domain was given none. */
    @JsonProperty("AvailabilityZoneCount")
    private Integer availabilityZoneCount;

    @JsonProperty("DedicatedMasterType")
    private String dedicatedMasterType;

    @JsonProperty("DedicatedMasterCount")
    private Integer dedicatedMasterCount;

    public ClusterConfig() {}

    public String getInstanceType() {
        return instanceType;
    }

    public void setInstanceType(String instanceType) {
        this.instanceType = instanceType;
    }

    public Integer getInstanceCount() {
        return instanceCount;
    }

    public void setInstanceCount(Integer instanceCount) {
        this.instanceCount = instanceCount;
    }

    public Boolean isDedicatedMasterEnabled() {
        return dedicatedMasterEnabled;
    }

    public void setDedicatedMasterEnabled(Boolean dedicatedMasterEnabled) {
        this.dedicatedMasterEnabled = dedicatedMasterEnabled;
    }

    public Integer getAvailabilityZoneCount() {
        return availabilityZoneCount;
    }

    public void setAvailabilityZoneCount(Integer availabilityZoneCount) {
        this.availabilityZoneCount = availabilityZoneCount;
    }

    public String getDedicatedMasterType() {
        return dedicatedMasterType;
    }

    public void setDedicatedMasterType(String dedicatedMasterType) {
        this.dedicatedMasterType = dedicatedMasterType;
    }

    public Integer getDedicatedMasterCount() {
        return dedicatedMasterCount;
    }

    public void setDedicatedMasterCount(Integer dedicatedMasterCount) {
        this.dedicatedMasterCount = dedicatedMasterCount;
    }

    public Boolean isZoneAwarenessEnabled() {
        return zoneAwarenessEnabled;
    }

    public void setZoneAwarenessEnabled(Boolean zoneAwarenessEnabled) {
        this.zoneAwarenessEnabled = zoneAwarenessEnabled;
    }
}

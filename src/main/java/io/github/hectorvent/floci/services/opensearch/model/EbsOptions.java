package io.github.hectorvent.floci.services.opensearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EbsOptions {

    @JsonProperty("EBSEnabled")
    private Boolean ebsEnabled = true;

    @JsonProperty("VolumeType")
    private String volumeType = "gp2";

    @JsonProperty("VolumeSize")
    private Integer volumeSize = 10;

    @JsonProperty("Iops")
    private Integer iops;

    @JsonProperty("Throughput")
    private Integer throughput;

    public EbsOptions() {}

    public Boolean isEbsEnabled() {
        return ebsEnabled;
    }

    public void setEbsEnabled(Boolean ebsEnabled) {
        this.ebsEnabled = ebsEnabled;
    }

    public String getVolumeType() {
        return volumeType;
    }

    public void setVolumeType(String volumeType) {
        this.volumeType = volumeType;
    }

    public Integer getIops() {
        return iops;
    }

    public void setIops(Integer iops) {
        this.iops = iops;
    }

    public Integer getThroughput() {
        return throughput;
    }

    public void setThroughput(Integer throughput) {
        this.throughput = throughput;
    }

    public Integer getVolumeSize() {
        return volumeSize;
    }

    public void setVolumeSize(Integer volumeSize) {
        this.volumeSize = volumeSize;
    }
}

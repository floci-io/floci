package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class EbsStorageInfo {

    @JsonProperty("provisionedThroughput")
    private ProvisionedThroughput provisionedThroughput;

    @JsonProperty("volumeSize")
    private Integer volumeSize;

    public EbsStorageInfo() {}

    public ProvisionedThroughput getProvisionedThroughput() { return provisionedThroughput; }
    public void setProvisionedThroughput(ProvisionedThroughput provisionedThroughput) { this.provisionedThroughput = provisionedThroughput; }

    public Integer getVolumeSize() { return volumeSize; }
    public void setVolumeSize(Integer volumeSize) { this.volumeSize = volumeSize; }
}
package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** EKS node group scaling configuration (min/max/desired worker count). */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodegroupScalingConfig {

    @JsonProperty("minSize")
    private Integer minSize;

    @JsonProperty("maxSize")
    private Integer maxSize;

    @JsonProperty("desiredSize")
    private Integer desiredSize;

    public NodegroupScalingConfig() {}

    public Integer getMinSize() { return minSize; }
    public void setMinSize(Integer minSize) { this.minSize = minSize; }

    public Integer getMaxSize() { return maxSize; }
    public void setMaxSize(Integer maxSize) { this.maxSize = maxSize; }

    public Integer getDesiredSize() { return desiredSize; }
    public void setDesiredSize(Integer desiredSize) { this.desiredSize = desiredSize; }
}

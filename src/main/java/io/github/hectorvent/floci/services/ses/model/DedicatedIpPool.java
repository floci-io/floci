package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * A SES V2 dedicated IP pool. Mirrors the AWS {@code DedicatedIpPool} shape:
 * a pool name and its scaling mode ({@code STANDARD} or {@code MANAGED}).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DedicatedIpPool {

    @JsonProperty("PoolName")
    private String poolName;

    @JsonProperty("ScalingMode")
    private String scalingMode;

    @JsonProperty("Tags")
    private List<Tag> tags = new ArrayList<>();

    public DedicatedIpPool() {}

    public DedicatedIpPool(String poolName, String scalingMode) {
        this.poolName = poolName;
        this.scalingMode = scalingMode;
    }

    public String getPoolName() { return poolName; }
    public void setPoolName(String poolName) { this.poolName = poolName; }

    public String getScalingMode() { return scalingMode; }
    public void setScalingMode(String scalingMode) { this.scalingMode = scalingMode; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) {
        this.tags = tags == null ? new ArrayList<>() : tags;
    }
}

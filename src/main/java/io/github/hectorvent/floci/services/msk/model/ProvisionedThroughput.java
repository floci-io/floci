package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProvisionedThroughput {

    @JsonProperty("enabled")
    private Boolean enabled;

    @JsonProperty("volumeThroughput")
    private Integer volumeThroughput;

    public ProvisionedThroughput() {}

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Integer getVolumeThroughput() { return volumeThroughput; }
    public void setVolumeThroughput(Integer volumeThroughput) { this.volumeThroughput = volumeThroughput; }
}
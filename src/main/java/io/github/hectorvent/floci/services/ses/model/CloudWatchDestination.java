package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudWatchDestination {

    @JsonProperty("DimensionConfigurations")
    private List<CloudWatchDimensionConfiguration> dimensionConfigurations = new ArrayList<>();

    public CloudWatchDestination() {}

    public List<CloudWatchDimensionConfiguration> getDimensionConfigurations() { return dimensionConfigurations; }
    public void setDimensionConfigurations(List<CloudWatchDimensionConfiguration> dimensionConfigurations) {
        this.dimensionConfigurations = dimensionConfigurations != null ? dimensionConfigurations : new ArrayList<>();
    }
}

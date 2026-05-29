package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudWatchDimensionConfiguration {

    @JsonProperty("DimensionName")
    private String dimensionName;

    @JsonProperty("DimensionValueSource")
    private String dimensionValueSource;

    @JsonProperty("DefaultDimensionValue")
    private String defaultDimensionValue;

    public CloudWatchDimensionConfiguration() {}

    public String getDimensionName() { return dimensionName; }
    public void setDimensionName(String dimensionName) { this.dimensionName = dimensionName; }

    public String getDimensionValueSource() { return dimensionValueSource; }
    public void setDimensionValueSource(String dimensionValueSource) { this.dimensionValueSource = dimensionValueSource; }

    public String getDefaultDimensionValue() { return defaultDimensionValue; }
    public void setDefaultDimensionValue(String defaultDimensionValue) { this.defaultDimensionValue = defaultDimensionValue; }
}

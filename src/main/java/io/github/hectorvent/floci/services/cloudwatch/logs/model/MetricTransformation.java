package io.github.hectorvent.floci.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** How a metric filter turns a matching log event into a metric value, the AWS MetricTransformation shape. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricTransformation {

    private String metricName;
    private String metricNamespace;
    private String metricValue;
    private Double defaultValue;
    private Map<String, String> dimensions = new LinkedHashMap<>();
    private String unit;

    public MetricTransformation() {}

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public String getMetricNamespace() { return metricNamespace; }
    public void setMetricNamespace(String metricNamespace) { this.metricNamespace = metricNamespace; }

    public String getMetricValue() { return metricValue; }
    public void setMetricValue(String metricValue) { this.metricValue = metricValue; }

    public Double getDefaultValue() { return defaultValue; }
    public void setDefaultValue(Double defaultValue) { this.defaultValue = defaultValue; }

    public Map<String, String> getDimensions() { return dimensions; }
    public void setDimensions(Map<String, String> dimensions) {
        this.dimensions = dimensions != null ? dimensions : new LinkedHashMap<>();
    }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
}

package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** One entry of a metric stream's {@code StatisticsConfigurations}. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricStreamStatisticsConfiguration {

    /** A single {@code IncludeMetrics} entry: a namespace plus a metric name. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IncludeMetric(String namespace, String metricName) {}

    private List<IncludeMetric> includeMetrics = new ArrayList<>();
    private List<String> additionalStatistics = new ArrayList<>();

    public List<IncludeMetric> getIncludeMetrics() { return includeMetrics; }
    public void setIncludeMetrics(List<IncludeMetric> includeMetrics) {
        this.includeMetrics = includeMetrics != null ? includeMetrics : new ArrayList<>();
    }

    public List<String> getAdditionalStatistics() { return additionalStatistics; }
    public void setAdditionalStatistics(List<String> additionalStatistics) {
        this.additionalStatistics = additionalStatistics != null ? additionalStatistics : new ArrayList<>();
    }
}

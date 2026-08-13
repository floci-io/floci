package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** One entry of a metric stream's {@code IncludeFilters} or {@code ExcludeFilters}. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricStreamFilter {

    private String namespace;
    private List<String> metricNames = new ArrayList<>();

    public MetricStreamFilter() {
    }

    public MetricStreamFilter(String namespace, List<String> metricNames) {
        this.namespace = namespace;
        this.metricNames = metricNames != null ? metricNames : new ArrayList<>();
    }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public List<String> getMetricNames() { return metricNames; }
    public void setMetricNames(List<String> metricNames) {
        this.metricNames = metricNames != null ? metricNames : new ArrayList<>();
    }
}

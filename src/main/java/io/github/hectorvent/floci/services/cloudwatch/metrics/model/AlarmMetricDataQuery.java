package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** The persisted AWS wire shape of a metric-math alarm query, including optional members. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlarmMetricDataQuery(
        @JsonProperty("Id") String id,
        @JsonProperty("Expression") String expression,
        @JsonProperty("Label") String label,
        @JsonProperty("ReturnData") Boolean returnData,
        @JsonProperty("Period") Integer period,
        @JsonProperty("AccountId") String accountId,
        @JsonProperty("MetricStat") MetricStat metricStat) {

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MetricStat(
            @JsonProperty("Metric") Metric metric,
            @JsonProperty("Period") Integer period,
            @JsonProperty("Stat") String stat,
            @JsonProperty("Unit") String unit) { }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Metric(
            @JsonProperty("Namespace") String namespace,
            @JsonProperty("MetricName") String metricName,
            @JsonProperty("Dimensions") List<MetricDimension> dimensions) { }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MetricDimension(
            @JsonProperty("Name") String name,
            @JsonProperty("Value") String value) { }
}

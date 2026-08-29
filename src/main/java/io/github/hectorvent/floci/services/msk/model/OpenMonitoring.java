package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The settings for open monitoring. CreateCluster names this shape OpenMonitoringInfo and the
 * response names it OpenMonitoring, but both carry the same single {@code prometheus} member,
 * so one model serves both directions.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenMonitoring {

    @JsonProperty("prometheus")
    private Prometheus prometheus;

    public OpenMonitoring() {}

    public Prometheus getPrometheus() { return prometheus; }
    public void setPrometheus(Prometheus prometheus) { this.prometheus = prometheus; }
}

package io.github.hectorvent.floci.services.opensearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A domain's off-peak window: whether it is on, and the UTC time its daily ten-hour window starts.
 * The API nests the start as {@code OffPeakWindow.WindowStartTime.Hours/Minutes}; the store keeps
 * the two numbers flat.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class OffPeakWindowOptions {

    @JsonProperty("Enabled")
    private Boolean enabled;

    @JsonProperty("StartHours")
    private Long startHours;

    @JsonProperty("StartMinutes")
    private Long startMinutes;

    public OffPeakWindowOptions() {}

    public OffPeakWindowOptions(Boolean enabled, Long startHours, Long startMinutes) {
        this.enabled = enabled;
        this.startHours = startHours;
        this.startMinutes = startMinutes;
    }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Long getStartHours() { return startHours; }
    public void setStartHours(Long startHours) { this.startHours = startHours; }

    public Long getStartMinutes() { return startMinutes; }
    public void setStartMinutes(Long startMinutes) { this.startMinutes = startMinutes; }
}

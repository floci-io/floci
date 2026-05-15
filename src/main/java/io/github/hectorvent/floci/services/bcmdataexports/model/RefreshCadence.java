package io.github.hectorvent.floci.services.bcmdataexports.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Cadence at which a BCM Data Exports {@code Export} refreshes its data.
 * AWS supports {@code FREQUENCY} = {@code SYNCHRONOUS} only at the time of writing.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RefreshCadence {

    private String frequency;       // SYNCHRONOUS

    public String getFrequency() { return frequency; }
    public void setFrequency(String v) { this.frequency = v; }
}

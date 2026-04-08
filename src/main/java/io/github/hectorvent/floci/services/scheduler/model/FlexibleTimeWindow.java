package io.github.hectorvent.floci.services.scheduler.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class FlexibleTimeWindow {

    private String mode;
    private Integer maximumWindowInMinutes;

    public FlexibleTimeWindow() {}

    public FlexibleTimeWindow(String mode, Integer maximumWindowInMinutes) {
        this.mode = mode;
        this.maximumWindowInMinutes = maximumWindowInMinutes;
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public Integer getMaximumWindowInMinutes() { return maximumWindowInMinutes; }
    public void setMaximumWindowInMinutes(Integer maximumWindowInMinutes) { this.maximumWindowInMinutes = maximumWindowInMinutes; }
}

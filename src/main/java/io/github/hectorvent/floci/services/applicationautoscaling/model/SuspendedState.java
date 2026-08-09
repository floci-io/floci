package io.github.hectorvent.floci.services.applicationautoscaling.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Suspension flags for a scalable target's automatic scaling activities.
 *
 * @see <a href="https://docs.aws.amazon.com/autoscaling/application/APIReference/API_SuspendedState.html">SuspendedState</a>
 */
@RegisterForReflection
public class SuspendedState {

    private boolean dynamicScalingInSuspended;
    private boolean dynamicScalingOutSuspended;
    private boolean scheduledScalingSuspended;

    public boolean isDynamicScalingInSuspended() { return dynamicScalingInSuspended; }
    public void setDynamicScalingInSuspended(boolean v) { this.dynamicScalingInSuspended = v; }

    public boolean isDynamicScalingOutSuspended() { return dynamicScalingOutSuspended; }
    public void setDynamicScalingOutSuspended(boolean v) { this.dynamicScalingOutSuspended = v; }

    public boolean isScheduledScalingSuspended() { return scheduledScalingSuspended; }
    public void setScheduledScalingSuspended(boolean v) { this.scheduledScalingSuspended = v; }
}

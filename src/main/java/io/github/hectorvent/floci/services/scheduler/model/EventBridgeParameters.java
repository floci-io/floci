package io.github.hectorvent.floci.services.scheduler.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * EventBridge Scheduler {@code Target.EventBridgeParameters}. Carries the
 * {@code DetailType} and {@code Source} used for the PutEvents entry when the
 * target is an EventBridge event bus (both required by AWS in that case).
 */
@RegisterForReflection
public class EventBridgeParameters {

    private String detailType;
    private String source;

    public EventBridgeParameters() {}

    public EventBridgeParameters(String detailType, String source) {
        this.detailType = detailType;
        this.source = source;
    }

    public String getDetailType() { return detailType; }
    public void setDetailType(String detailType) { this.detailType = detailType; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}

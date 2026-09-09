package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class NotificationProperty {
    @JsonProperty("NotifyDelayAfter")
    private Integer notifyDelayAfter;

    public NotificationProperty() {}

    public Integer getNotifyDelayAfter() { return notifyDelayAfter; }
    public void setNotifyDelayAfter(Integer notifyDelayAfter) { this.notifyDelayAfter = notifyDelayAfter; }
}

package io.floci.aws.services.rolesanywhere.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One notification setting of a trust anchor, as {@code NotificationSettingDetail} reports it: the event it is
 * for, the channel it notifies, whether it is on, how many days before expiry it fires and who configured it.
 */
@RegisterForReflection
public class NotificationSetting {

    private String event;
    private String channel;
    private boolean enabled;
    private Integer threshold;
    private String configuredBy;

    public NotificationSetting() {
    }

    public NotificationSetting(String event, String channel, boolean enabled, Integer threshold,
                               String configuredBy) {
        this.event = event;
        this.channel = channel;
        this.enabled = enabled;
        this.threshold = threshold;
        this.configuredBy = configuredBy;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getThreshold() {
        return threshold;
    }

    public void setThreshold(Integer threshold) {
        this.threshold = threshold;
    }

    public String getConfiguredBy() {
        return configuredBy;
    }

    public void setConfiguredBy(String configuredBy) {
        this.configuredBy = configuredBy;
    }
}

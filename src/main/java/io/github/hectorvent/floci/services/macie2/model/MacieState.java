package io.github.hectorvent.floci.services.macie2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MacieState {
    private String adminAccountId;
    private boolean enabled;
    private boolean autoEnable;

    public MacieState() {}
    public String getAdminAccountId() { return adminAccountId; }
    public void setAdminAccountId(String adminAccountId) { this.adminAccountId = adminAccountId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isAutoEnable() { return autoEnable; }
    public void setAutoEnable(boolean autoEnable) { this.autoEnable = autoEnable; }
}

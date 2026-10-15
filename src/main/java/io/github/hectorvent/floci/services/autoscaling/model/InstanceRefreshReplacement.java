package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstanceRefreshReplacement {
    private String originalInstanceId;
    private String replacementInstanceId;
    private String launchClientToken;
    private String phase = "Selected";
    private InstanceRefreshLaunch launch;

    public InstanceRefreshLaunch getLaunch() { return launch; }
    public void setLaunch(InstanceRefreshLaunch value) { launch = value; }

    public String getPhase() { return phase; }
    public void setPhase(String value) { phase = value; }

    public boolean isComplete() { return "Complete".equals(phase) || "NoLongerRequired".equals(phase); }


    public String getOriginalInstanceId() { return originalInstanceId; }
    public void setOriginalInstanceId(String value) { originalInstanceId = value; }

    public String getReplacementInstanceId() { return replacementInstanceId; }
    public void setReplacementInstanceId(String value) { replacementInstanceId = value; }

    public String getLaunchClientToken() { return launchClientToken; }
    public void setLaunchClientToken(String value) { launchClientToken = value; }
}

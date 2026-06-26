package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstanceRefresh {

    private String instanceRefreshId;
    private String autoScalingGroupName;
    private String strategy = "Rolling";
    private String status;
    private String statusReason;
    private int percentageComplete;
    private int instancesToUpdate;
    private Instant startTime;
    private Instant endTime;
    private String region;

    private String desiredLaunchTemplateId;
    private String desiredLaunchTemplateName;
    private String desiredLaunchTemplateVersion;

    private Integer minHealthyPercentage;
    private Integer maxHealthyPercentage;
    private Integer instanceWarmup;
    private Boolean skipMatching;
    private Boolean autoRollback;
    private String scaleInProtectedInstances;
    private String standbyInstances;
    private Integer checkpointDelay;
    private Integer bakeTime;
    private List<Integer> checkpointPercentages = new ArrayList<>();

    public InstanceRefresh() {}

    public String getInstanceRefreshId() { return instanceRefreshId; }
    public void setInstanceRefreshId(String v) { this.instanceRefreshId = v; }

    public String getAutoScalingGroupName() { return autoScalingGroupName; }
    public void setAutoScalingGroupName(String v) { this.autoScalingGroupName = v; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String v) { this.strategy = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String v) { this.statusReason = v; }

    public int getPercentageComplete() { return percentageComplete; }
    public void setPercentageComplete(int v) { this.percentageComplete = v; }

    public int getInstancesToUpdate() { return instancesToUpdate; }
    public void setInstancesToUpdate(int v) { this.instancesToUpdate = v; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant v) { this.startTime = v; }

    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant v) { this.endTime = v; }

    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }

    public String getDesiredLaunchTemplateId() { return desiredLaunchTemplateId; }
    public void setDesiredLaunchTemplateId(String v) { this.desiredLaunchTemplateId = v; }

    public String getDesiredLaunchTemplateName() { return desiredLaunchTemplateName; }
    public void setDesiredLaunchTemplateName(String v) { this.desiredLaunchTemplateName = v; }

    public String getDesiredLaunchTemplateVersion() { return desiredLaunchTemplateVersion; }
    public void setDesiredLaunchTemplateVersion(String v) { this.desiredLaunchTemplateVersion = v; }

    public Integer getMinHealthyPercentage() { return minHealthyPercentage; }
    public void setMinHealthyPercentage(Integer v) { this.minHealthyPercentage = v; }

    public Integer getMaxHealthyPercentage() { return maxHealthyPercentage; }
    public void setMaxHealthyPercentage(Integer v) { this.maxHealthyPercentage = v; }

    public Integer getInstanceWarmup() { return instanceWarmup; }
    public void setInstanceWarmup(Integer v) { this.instanceWarmup = v; }

    public Boolean getSkipMatching() { return skipMatching; }
    public void setSkipMatching(Boolean v) { this.skipMatching = v; }

    public Boolean getAutoRollback() { return autoRollback; }
    public void setAutoRollback(Boolean v) { this.autoRollback = v; }

    public String getScaleInProtectedInstances() { return scaleInProtectedInstances; }
    public void setScaleInProtectedInstances(String v) { this.scaleInProtectedInstances = v; }

    public String getStandbyInstances() { return standbyInstances; }
    public void setStandbyInstances(String v) { this.standbyInstances = v; }

    public Integer getCheckpointDelay() { return checkpointDelay; }
    public void setCheckpointDelay(Integer v) { this.checkpointDelay = v; }

    public Integer getBakeTime() { return bakeTime; }
    public void setBakeTime(Integer v) { this.bakeTime = v; }

    public List<Integer> getCheckpointPercentages() { return checkpointPercentages; }
    public void setCheckpointPercentages(List<Integer> v) {
        this.checkpointPercentages = v != null ? v : new ArrayList<>();
    }

    public boolean hasDesiredConfiguration() {
        return desiredLaunchTemplateId != null
                || desiredLaunchTemplateName != null
                || desiredLaunchTemplateVersion != null;
    }

    public boolean hasPreferences() {
        return minHealthyPercentage != null
                || maxHealthyPercentage != null
                || instanceWarmup != null
                || skipMatching != null
                || autoRollback != null
                || scaleInProtectedInstances != null
                || standbyInstances != null
                || checkpointDelay != null
                || bakeTime != null
                || !checkpointPercentages.isEmpty();
    }
}

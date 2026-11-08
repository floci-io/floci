package io.floci.aws.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LifecycleHook {

    private String lifecycleHookName;
    private String autoScalingGroupName;
    private String lifecycleTransition;  // autoscaling:EC2_INSTANCE_LAUNCHING | autoscaling:EC2_INSTANCE_TERMINATING
    private String notificationTargetArn;
    private String roleArn;
    private String notificationMetadata;
    private int heartbeatTimeout = 3600;
    private int globalTimeout = 172800;
    private String defaultResult = "ABANDON";  // CONTINUE | ABANDON

    public LifecycleHook() {}

    public String getLifecycleHookName() { return lifecycleHookName; }
    public void setLifecycleHookName(String v) { this.lifecycleHookName = v; }

    public String getAutoScalingGroupName() { return autoScalingGroupName; }
    public void setAutoScalingGroupName(String v) { this.autoScalingGroupName = v; }

    public String getLifecycleTransition() { return lifecycleTransition; }
    public void setLifecycleTransition(String v) { this.lifecycleTransition = v; }

    public String getNotificationTargetArn() { return notificationTargetArn; }
    public void setNotificationTargetArn(String v) { this.notificationTargetArn = v; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String v) { this.roleArn = v; }

    public String getNotificationMetadata() { return notificationMetadata; }
    public void setNotificationMetadata(String v) { this.notificationMetadata = v; }

    public int getHeartbeatTimeout() { return heartbeatTimeout; }
    public void setHeartbeatTimeout(int v) { this.heartbeatTimeout = v; }

    public int getGlobalTimeout() { return globalTimeout; }
    public void setGlobalTimeout(int v) { this.globalTimeout = v; }

    public String getDefaultResult() { return defaultResult; }
    public void setDefaultResult(String v) { this.defaultResult = v; }
}

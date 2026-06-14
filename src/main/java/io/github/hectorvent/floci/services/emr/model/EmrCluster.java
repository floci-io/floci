package io.github.hectorvent.floci.services.emr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An EMR cluster (job flow). State machine:
 * STARTING → BOOTSTRAPPING → RUNNING → WAITING, then → TERMINATING → TERMINATED.
 * Steps, instance groups and fleets are stored as nested lists. Complex sub-structures
 * (ec2 attributes, applications, configurations) are stored as raw JSON strings.
 */
@RegisterForReflection
public class EmrCluster {

    private String id;
    private String name;
    private String state = "STARTING";
    private String stateChangeReasonCode;
    private String stateChangeMessage;
    private String releaseLabel;
    private String logUri;
    private String serviceRole;
    private String jobFlowRole;
    private String autoScalingRole;
    private String scaleDownBehavior;
    private String securityConfiguration;
    private String customAmiId;
    private String masterPublicDnsName;
    private String clusterArn;
    private String instanceCollectionType;
    private boolean terminationProtected;
    private boolean visibleToAllUsers = true;
    private boolean autoTerminate;
    private boolean unhealthyNodeReplacement;
    private boolean keepJobFlowAliveWhenNoSteps;
    private int normalizedInstanceHours;
    private int stepConcurrencyLevel = 1;
    private int ebsRootVolumeSize;
    private String ec2Attributes;
    private String applications;
    private String configurations;
    private String region;
    private Instant creationDateTime;
    private Instant readyDateTime;
    private Instant endDateTime;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<EmrStep> steps = new ArrayList<>();
    private List<EmrInstanceGroup> instanceGroups = new ArrayList<>();
    private List<EmrInstanceFleet> instanceFleets = new ArrayList<>();

    public EmrCluster() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getStateChangeReasonCode() { return stateChangeReasonCode; }
    public void setStateChangeReasonCode(String stateChangeReasonCode) {
        this.stateChangeReasonCode = stateChangeReasonCode;
    }

    public String getStateChangeMessage() { return stateChangeMessage; }
    public void setStateChangeMessage(String stateChangeMessage) { this.stateChangeMessage = stateChangeMessage; }

    public String getReleaseLabel() { return releaseLabel; }
    public void setReleaseLabel(String releaseLabel) { this.releaseLabel = releaseLabel; }

    public String getLogUri() { return logUri; }
    public void setLogUri(String logUri) { this.logUri = logUri; }

    public String getServiceRole() { return serviceRole; }
    public void setServiceRole(String serviceRole) { this.serviceRole = serviceRole; }

    public String getJobFlowRole() { return jobFlowRole; }
    public void setJobFlowRole(String jobFlowRole) { this.jobFlowRole = jobFlowRole; }

    public String getAutoScalingRole() { return autoScalingRole; }
    public void setAutoScalingRole(String autoScalingRole) { this.autoScalingRole = autoScalingRole; }

    public String getScaleDownBehavior() { return scaleDownBehavior; }
    public void setScaleDownBehavior(String scaleDownBehavior) { this.scaleDownBehavior = scaleDownBehavior; }

    public String getSecurityConfiguration() { return securityConfiguration; }
    public void setSecurityConfiguration(String securityConfiguration) {
        this.securityConfiguration = securityConfiguration;
    }

    public String getCustomAmiId() { return customAmiId; }
    public void setCustomAmiId(String customAmiId) { this.customAmiId = customAmiId; }

    public String getMasterPublicDnsName() { return masterPublicDnsName; }
    public void setMasterPublicDnsName(String masterPublicDnsName) {
        this.masterPublicDnsName = masterPublicDnsName;
    }

    public String getClusterArn() { return clusterArn; }
    public void setClusterArn(String clusterArn) { this.clusterArn = clusterArn; }

    public String getInstanceCollectionType() { return instanceCollectionType; }
    public void setInstanceCollectionType(String instanceCollectionType) {
        this.instanceCollectionType = instanceCollectionType;
    }

    public boolean isTerminationProtected() { return terminationProtected; }
    public void setTerminationProtected(boolean terminationProtected) {
        this.terminationProtected = terminationProtected;
    }

    public boolean isVisibleToAllUsers() { return visibleToAllUsers; }
    public void setVisibleToAllUsers(boolean visibleToAllUsers) { this.visibleToAllUsers = visibleToAllUsers; }

    public boolean isAutoTerminate() { return autoTerminate; }
    public void setAutoTerminate(boolean autoTerminate) { this.autoTerminate = autoTerminate; }

    public boolean isUnhealthyNodeReplacement() { return unhealthyNodeReplacement; }
    public void setUnhealthyNodeReplacement(boolean unhealthyNodeReplacement) {
        this.unhealthyNodeReplacement = unhealthyNodeReplacement;
    }

    public boolean isKeepJobFlowAliveWhenNoSteps() { return keepJobFlowAliveWhenNoSteps; }
    public void setKeepJobFlowAliveWhenNoSteps(boolean keepJobFlowAliveWhenNoSteps) {
        this.keepJobFlowAliveWhenNoSteps = keepJobFlowAliveWhenNoSteps;
    }

    public int getNormalizedInstanceHours() { return normalizedInstanceHours; }
    public void setNormalizedInstanceHours(int normalizedInstanceHours) {
        this.normalizedInstanceHours = normalizedInstanceHours;
    }

    public int getStepConcurrencyLevel() { return stepConcurrencyLevel; }
    public void setStepConcurrencyLevel(int stepConcurrencyLevel) {
        this.stepConcurrencyLevel = stepConcurrencyLevel;
    }

    public int getEbsRootVolumeSize() { return ebsRootVolumeSize; }
    public void setEbsRootVolumeSize(int ebsRootVolumeSize) { this.ebsRootVolumeSize = ebsRootVolumeSize; }

    public String getEc2Attributes() { return ec2Attributes; }
    public void setEc2Attributes(String ec2Attributes) { this.ec2Attributes = ec2Attributes; }

    public String getApplications() { return applications; }
    public void setApplications(String applications) { this.applications = applications; }

    public String getConfigurations() { return configurations; }
    public void setConfigurations(String configurations) { this.configurations = configurations; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Instant getCreationDateTime() { return creationDateTime; }
    public void setCreationDateTime(Instant creationDateTime) { this.creationDateTime = creationDateTime; }

    public Instant getReadyDateTime() { return readyDateTime; }
    public void setReadyDateTime(Instant readyDateTime) { this.readyDateTime = readyDateTime; }

    public Instant getEndDateTime() { return endDateTime; }
    public void setEndDateTime(Instant endDateTime) { this.endDateTime = endDateTime; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public List<EmrStep> getSteps() { return steps; }
    public void setSteps(List<EmrStep> steps) { this.steps = steps; }

    public List<EmrInstanceGroup> getInstanceGroups() { return instanceGroups; }
    public void setInstanceGroups(List<EmrInstanceGroup> instanceGroups) { this.instanceGroups = instanceGroups; }

    public List<EmrInstanceFleet> getInstanceFleets() { return instanceFleets; }
    public void setInstanceFleets(List<EmrInstanceFleet> instanceFleets) { this.instanceFleets = instanceFleets; }
}

package io.floci.aws.services.emr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** An EMR instance group (MASTER | CORE | TASK). */
@RegisterForReflection
public class EmrInstanceGroup {

    private String id;
    private String name;
    private String instanceGroupType;
    private String instanceType;
    private String market;
    private String bidPrice;
    private int requestedInstanceCount;
    private int runningInstanceCount;
    private int terminatedInstanceCount;
    // The ordinals of its instances EC2InstanceIdsToTerminate ended, ascending: none of its instances takes them again,
    // so the others keep theirs. Floci's own.
    private List<Integer> terminatedOrdinals = new ArrayList<>();
    private String state;
    /** The AutoScalingPolicy as given (Constraints and Rules), raw JSON; null when none is attached. */
    private String autoScalingPolicy;
    /** The group's Configurations as given, raw JSON; null when none. Applied at once, so it is also the last applied. */
    private String configurations;
    private long configurationsVersion;
    /** The EbsConfiguration as given (EbsBlockDeviceConfigs and EbsOptimized), raw JSON; null when none. */
    private String ebsConfiguration;
    private String customAmiId;
    /** The ShrinkPolicy the last ModifyInstanceGroups gave, raw JSON; null when none. */
    private String shrinkPolicy;

    public EmrInstanceGroup() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getInstanceGroupType() { return instanceGroupType; }
    public void setInstanceGroupType(String instanceGroupType) { this.instanceGroupType = instanceGroupType; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }

    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }

    public String getConfigurations() { return configurations; }
    public void setConfigurations(String configurations) { this.configurations = configurations; }

    public long getConfigurationsVersion() { return configurationsVersion; }
    public void setConfigurationsVersion(long configurationsVersion) { this.configurationsVersion = configurationsVersion; }

    public String getBidPrice() { return bidPrice; }
    public void setBidPrice(String bidPrice) { this.bidPrice = bidPrice; }

    public int getRequestedInstanceCount() { return requestedInstanceCount; }
    public void setRequestedInstanceCount(int requestedInstanceCount) {
        this.requestedInstanceCount = requestedInstanceCount;
    }

    public int getRunningInstanceCount() { return runningInstanceCount; }
    public void setRunningInstanceCount(int runningInstanceCount) {
        this.runningInstanceCount = runningInstanceCount;
    }

    public int getTerminatedInstanceCount() { return terminatedInstanceCount; }
    public void setTerminatedInstanceCount(int terminatedInstanceCount) {
        this.terminatedInstanceCount = terminatedInstanceCount;
    }

    public List<Integer> getTerminatedOrdinals() { return terminatedOrdinals; }
    public void setTerminatedOrdinals(List<Integer> terminatedOrdinals) {
        this.terminatedOrdinals = terminatedOrdinals != null ? terminatedOrdinals : new ArrayList<>();
    }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getAutoScalingPolicy() { return autoScalingPolicy; }
    public void setAutoScalingPolicy(String autoScalingPolicy) { this.autoScalingPolicy = autoScalingPolicy; }

    public String getEbsConfiguration() { return ebsConfiguration; }
    public void setEbsConfiguration(String ebsConfiguration) { this.ebsConfiguration = ebsConfiguration; }

    public String getCustomAmiId() { return customAmiId; }
    public void setCustomAmiId(String customAmiId) { this.customAmiId = customAmiId; }

    public String getShrinkPolicy() { return shrinkPolicy; }
    public void setShrinkPolicy(String shrinkPolicy) { this.shrinkPolicy = shrinkPolicy; }
}

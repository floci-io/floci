package io.github.hectorvent.floci.services.emr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

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
    private String state;
    /** The AutoScalingPolicy as given (Constraints and Rules), raw JSON; null when none is attached. */
    private String autoScalingPolicy;
    /** The group's Configurations as given, raw JSON; null when none. Applied at once, so it is also the last applied. */
    private String configurations;
    private long configurationsVersion;

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

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getAutoScalingPolicy() { return autoScalingPolicy; }
    public void setAutoScalingPolicy(String autoScalingPolicy) { this.autoScalingPolicy = autoScalingPolicy; }
}

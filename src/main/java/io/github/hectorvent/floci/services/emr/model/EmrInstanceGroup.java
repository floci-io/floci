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
}

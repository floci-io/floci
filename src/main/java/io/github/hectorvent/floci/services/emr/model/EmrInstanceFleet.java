package io.github.hectorvent.floci.services.emr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** An EMR instance fleet (MASTER | CORE | TASK). */
@RegisterForReflection
public class EmrInstanceFleet {

    private String id;
    private String name;
    private String instanceFleetType;
    private int targetOnDemandCapacity;
    private int targetSpotCapacity;
    private int provisionedOnDemandCapacity;
    private int provisionedSpotCapacity;
    private String state;

    public EmrInstanceFleet() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getInstanceFleetType() { return instanceFleetType; }
    public void setInstanceFleetType(String instanceFleetType) { this.instanceFleetType = instanceFleetType; }

    public int getTargetOnDemandCapacity() { return targetOnDemandCapacity; }
    public void setTargetOnDemandCapacity(int targetOnDemandCapacity) {
        this.targetOnDemandCapacity = targetOnDemandCapacity;
    }

    public int getTargetSpotCapacity() { return targetSpotCapacity; }
    public void setTargetSpotCapacity(int targetSpotCapacity) { this.targetSpotCapacity = targetSpotCapacity; }

    public int getProvisionedOnDemandCapacity() { return provisionedOnDemandCapacity; }
    public void setProvisionedOnDemandCapacity(int provisionedOnDemandCapacity) {
        this.provisionedOnDemandCapacity = provisionedOnDemandCapacity;
    }

    public int getProvisionedSpotCapacity() { return provisionedSpotCapacity; }
    public void setProvisionedSpotCapacity(int provisionedSpotCapacity) {
        this.provisionedSpotCapacity = provisionedSpotCapacity;
    }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
}

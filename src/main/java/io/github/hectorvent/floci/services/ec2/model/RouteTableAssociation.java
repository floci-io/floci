package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class RouteTableAssociation {

    private String routeTableAssociationId;
    private String routeTableId;
    private String subnetId;
    private boolean main;
    private String associationState = "associated";

    public RouteTableAssociation() {}

    public String getRouteTableAssociationId() { return routeTableAssociationId; }
    public void setRouteTableAssociationId(String routeTableAssociationId) { this.routeTableAssociationId = routeTableAssociationId; }

    public String getRouteTableId() { return routeTableId; }
    public void setRouteTableId(String routeTableId) { this.routeTableId = routeTableId; }

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public boolean isMain() { return main; }
    public void setMain(boolean main) { this.main = main; }

    public String getAssociationState() { return associationState; }
    public void setAssociationState(String associationState) { this.associationState = associationState; }
}

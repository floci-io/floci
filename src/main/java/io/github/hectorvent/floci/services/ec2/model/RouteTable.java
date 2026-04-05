package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class RouteTable {

    private String routeTableId;
    private String vpcId;
    private String ownerId;
    private String region;
    private List<Route> routes = new ArrayList<>();
    private List<RouteTableAssociation> associations = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();

    public RouteTable() {}

    public String getRouteTableId() { return routeTableId; }
    public void setRouteTableId(String routeTableId) { this.routeTableId = routeTableId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Route> getRoutes() { return routes; }
    public void setRoutes(List<Route> routes) { this.routes = routes; }

    public List<RouteTableAssociation> getAssociations() { return associations; }
    public void setAssociations(List<RouteTableAssociation> associations) { this.associations = associations; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}

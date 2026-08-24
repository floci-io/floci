package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A transit gateway route table.
 *
 * <p>AWS exposes routes, associations and propagations through separate operations
 * ({@code SearchTransitGatewayRoutes}, {@code GetTransitGatewayRouteTableAssociations},
 * {@code GetTransitGatewayRouteTablePropagations}) rather than on the describe, so they are
 * held here as storage detail and never written into the route table's own XML.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitGatewayRouteTable {

    private String transitGatewayRouteTableId;
    private String transitGatewayId;
    private String state = "available";
    private boolean defaultAssociationRouteTable;
    private boolean defaultPropagationRouteTable;
    private Instant creationTime;
    private String region;
    private List<TransitGatewayRoute> routes = new ArrayList<>();
    private List<TransitGatewayRouteTableAssociation> associations = new ArrayList<>();
    private List<TransitGatewayRouteTablePropagation> propagations = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();

    public TransitGatewayRouteTable() {}

    public String getTransitGatewayRouteTableId() { return transitGatewayRouteTableId; }
    public void setTransitGatewayRouteTableId(String transitGatewayRouteTableId) {
        this.transitGatewayRouteTableId = transitGatewayRouteTableId;
    }

    public String getTransitGatewayId() { return transitGatewayId; }
    public void setTransitGatewayId(String transitGatewayId) { this.transitGatewayId = transitGatewayId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public boolean isDefaultAssociationRouteTable() { return defaultAssociationRouteTable; }
    public void setDefaultAssociationRouteTable(boolean defaultAssociationRouteTable) {
        this.defaultAssociationRouteTable = defaultAssociationRouteTable;
    }

    public boolean isDefaultPropagationRouteTable() { return defaultPropagationRouteTable; }
    public void setDefaultPropagationRouteTable(boolean defaultPropagationRouteTable) {
        this.defaultPropagationRouteTable = defaultPropagationRouteTable;
    }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<TransitGatewayRoute> getRoutes() { return routes; }
    public void setRoutes(List<TransitGatewayRoute> routes) { this.routes = routes; }

    public List<TransitGatewayRouteTableAssociation> getAssociations() { return associations; }
    public void setAssociations(List<TransitGatewayRouteTableAssociation> associations) {
        this.associations = associations;
    }

    public List<TransitGatewayRouteTablePropagation> getPropagations() { return propagations; }
    public void setPropagations(List<TransitGatewayRouteTablePropagation> propagations) {
        this.propagations = propagations;
    }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}

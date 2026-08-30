package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * A transit gateway route table. Creating a transit gateway with
 * {@code DefaultRouteTableAssociation} or {@code DefaultRouteTablePropagation} enabled makes AWS
 * mint one of these immediately, and its id is reported back on the gateway's options, so the
 * record exists here from part one. The actions that operate on route tables directly
 * (create, associate, propagate, describe) are not implemented yet.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitGatewayRouteTable {

    private String transitGatewayRouteTableId;
    private String transitGatewayId;
    private String state;
    private boolean defaultAssociationRouteTable;
    private boolean defaultPropagationRouteTable;
    private String creationTime;
    private String region;
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

    public String getCreationTime() { return creationTime; }
    public void setCreationTime(String creationTime) { this.creationTime = creationTime; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}

package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * An entry in a transit gateway route table. {@code type} is {@code static} for a route a
 * caller created and {@code propagated} for one a route table propagation produced.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitGatewayRoute {

    private String destinationCidrBlock;
    private String prefixListId;
    private String type = "static";
    private String state = "active";
    private List<TransitGatewayRouteAttachment> transitGatewayAttachments = new ArrayList<>();

    public TransitGatewayRoute() {}

    public String getDestinationCidrBlock() { return destinationCidrBlock; }
    public void setDestinationCidrBlock(String destinationCidrBlock) {
        this.destinationCidrBlock = destinationCidrBlock;
    }

    public String getPrefixListId() { return prefixListId; }
    public void setPrefixListId(String prefixListId) { this.prefixListId = prefixListId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public List<TransitGatewayRouteAttachment> getTransitGatewayAttachments() {
        return transitGatewayAttachments;
    }

    public void setTransitGatewayAttachments(List<TransitGatewayRouteAttachment> transitGatewayAttachments) {
        this.transitGatewayAttachments = transitGatewayAttachments;
    }
}

package io.github.hectorvent.floci.services.lambdamicrovms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService.NetworkConnector;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * Lambda network connectors ("Lambda Core", apiVersion 2026-04-30, rest-json,
 * signs as {@code lambda}). Note the member casing: this service model uses
 * PascalCase ({@code Name}, {@code Configuration.VpcEgressConfiguration}),
 * unlike the camelCase Lambda Microvms model.
 */
@Path("/2026-04-04")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaNetworkConnectorsController {

    private final LambdaMicrovmsService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaNetworkConnectorsController(LambdaMicrovmsService service,
                                             RegionResolver regionResolver,
                                             ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/network-connectors")
    public Response createNetworkConnector(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = parse(body);
        JsonNode vpc = request.path("Configuration").path("VpcEgressConfiguration");
        NetworkConnector connector = service.createConnector(
                region,
                regionResolver.getAccountId(),
                text(request, "Name"),
                stringList(vpc.path("SubnetIds")),
                stringList(vpc.path("SecurityGroupIds")),
                text(request, "OperatorRole"),
                text(request, "ClientToken"),
                stringList(vpc.path("AssociatedComputeResourceTypes")),
                text(vpc, "NetworkProtocol"));
        return Response.status(202).entity(connectorNode(connector, ConnectorShape.CREATE)).build();
    }

    @GET
    @Path("/network-connectors")
    public Response listNetworkConnectors(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("NetworkConnectors");
        for (NetworkConnector connector : service.listConnectors(region)) {
            items.add(connectorSummaryNode(connector));
        }
        // The recorded list omits the pagination token entirely rather than
        // sending it as null, and a client looping until it is null would
        // otherwise never stop.
        return Response.ok(root).build();
    }

    @GET
    @Path("/network-connectors/{identifier}")
    public Response getNetworkConnector(@Context HttpHeaders headers,
                                        @PathParam("identifier") String identifier) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(connectorNode(service.getConnector(region, identifier), ConnectorShape.GET)).build();
    }

    @PUT
    @Path("/network-connectors/{identifier}")
    public Response updateNetworkConnector(@Context HttpHeaders headers,
                                           @PathParam("identifier") String identifier,
                                           String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = parse(body);
        JsonNode vpc = request.path("Configuration").path("VpcEgressConfiguration");
        NetworkConnector connector = service.updateConnector(
                region, identifier, stringList(vpc.path("SubnetIds")), stringList(vpc.path("SecurityGroupIds")));
        return Response.status(202).entity(connectorNode(connector, ConnectorShape.UPDATE)).build();
    }

    @DELETE
    @Path("/network-connectors/{identifier}")
    public Response deleteNetworkConnector(@Context HttpHeaders headers,
                                           @PathParam("identifier") String identifier) {
        String region = regionResolver.resolveRegion(headers);
        // Read the connector before it goes: the delete answers 202 with the
        // connector's own body in DELETING, not an empty object.
        NetworkConnector doomed = service.getConnector(region, identifier);
        ObjectNode body = connectorNode(doomed, ConnectorShape.DELETE);
        body.put("State", "DELETING");
        service.deleteConnector(region, identifier);
        return Response.status(202).entity(body).build();
    }

    /**
     * The four shapes a connector is returned in. Create and Delete return a
     * base six; Get adds LastModified and StateReason; Update adds the update
     * status but not StateReason; and only the list summary carries Type.
     * Absent means absent — a connector that has never been updated has no
     * LastUpdateStatus at all, and sending one as null diverges on every read.
     */
    private enum ConnectorShape { CREATE, GET, UPDATE, DELETE }

    private ObjectNode connectorNode(NetworkConnector connector, ConnectorShape shape) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", connector.id);
        node.put("Name", connector.name);
        node.put("Arn", connector.arn);
        node.put("State", shape == ConnectorShape.CREATE ? "PENDING" : connector.state);
        if (connector.operatorRole == null) {
            node.putNull("OperatorRole");
        } else {
            node.put("OperatorRole", connector.operatorRole);
        }
        ObjectNode vpc = node.putObject("Configuration").putObject("VpcEgressConfiguration");
        ArrayNode subnets = vpc.putArray("SubnetIds");
        connector.subnetIds.forEach(subnets::add);
        ArrayNode sgs = vpc.putArray("SecurityGroupIds");
        connector.securityGroupIds.forEach(sgs::add);
        // Both arrive on the request; echoing them is what lets a client read
        // back what it set.
        ArrayNode types = vpc.putArray("AssociatedComputeResourceTypes");
        connector.associatedComputeResourceTypes.forEach(types::add);
        if (connector.networkProtocol != null) {
            vpc.put("NetworkProtocol", connector.networkProtocol);
        }

        if (shape == ConnectorShape.GET || shape == ConnectorShape.UPDATE) {
            node.put("LastModified", iso(connector.lastModified));
        }
        if (shape == ConnectorShape.GET && connector.stateReason != null) {
            node.put("StateReason", connector.stateReason);
        }
        if (shape == ConnectorShape.UPDATE) {
            if (connector.lastUpdateStatus != null) {
                node.put("LastUpdateStatus", connector.lastUpdateStatus);
            }
            if (connector.lastUpdateStatusReason != null) {
                node.put("LastUpdateStatusReason", connector.lastUpdateStatusReason);
            }
        }
        return node;
    }

    /** The list summary, the only shape carrying Type. */
    private ObjectNode connectorSummaryNode(NetworkConnector connector) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", connector.arn);
        node.put("Name", connector.name);
        node.put("Id", connector.id);
        node.put("Type", "VPC_EGRESS");
        node.put("State", connector.state);
        node.put("LastModified", iso(connector.lastModified));
        return node;
    }

    private static String iso(java.time.Instant when) {
        return java.time.format.DateTimeFormatter.ISO_INSTANT
                .format((when == null ? java.time.Instant.now() : when)
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", "Malformed request body", 400);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> out.add(item.asText()));
        return out;
    }
}

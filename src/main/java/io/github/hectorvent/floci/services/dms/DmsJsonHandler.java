package io.github.hectorvent.floci.services.dms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.dms.model.ReplicationSubnetGroup;
import io.github.hectorvent.floci.services.dms.model.ResourceTag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class DmsJsonHandler {

    private final DmsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public DmsJsonHandler(DmsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateReplicationSubnetGroup" -> {
                ObjectNode response = objectMapper.createObjectNode();
                response.set("ReplicationSubnetGroup",
                        subnetGroup(service.createReplicationSubnetGroup(request, region)));
                yield Response.ok(response).build();
            }
            case "DescribeReplicationSubnetGroups" -> {
                PaginatedResult<ReplicationSubnetGroup> page =
                        service.describeReplicationSubnetGroups(request, region);
                ObjectNode response = objectMapper.createObjectNode();
                ArrayNode items = response.putArray("ReplicationSubnetGroups");
                page.items().forEach(group -> items.add(subnetGroup(group)));
                // Marker is present only while another page remains: an SDK paginator treats any
                // Marker at all as "ask again", so emitting one on the final page loops forever.
                if (page.nextToken() != null) {
                    response.put("Marker", page.nextToken());
                }
                yield Response.ok(response).build();
            }
            case "DeleteReplicationSubnetGroup" -> {
                service.deleteReplicationSubnetGroup(request, region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            case "ListTagsForResource" -> {
                ObjectNode response = objectMapper.createObjectNode();
                ArrayNode tagList = response.putArray("TagList");
                service.listTagsForResource(request, region).forEach(tag -> tagList.add(tagNode(tag)));
                yield Response.ok(response).build();
            }
            case "AddTagsToResource" -> {
                service.addTagsToResource(request, region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            case "RemoveTagsFromResource" -> {
                service.removeTagsFromResource(request, region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            default -> null;
        };
    }

    private ObjectNode tagNode(ResourceTag tag) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Key", tag.key());
        node.put("Value", tag.value());
        if (tag.resourceArn() != null) {
            node.put("ResourceArn", tag.resourceArn());
        }
        return node;
    }

    private ObjectNode subnetGroup(ReplicationSubnetGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ReplicationSubnetGroupIdentifier", group.getReplicationSubnetGroupIdentifier());
        node.put("ReplicationSubnetGroupDescription", group.getReplicationSubnetGroupDescription());
        node.put("VpcId", group.getVpcId());
        node.put("SubnetGroupStatus", group.getSubnetGroupStatus());
        ArrayNode subnets = node.putArray("Subnets");
        group.getSubnetIds().forEach(subnetId -> {
            ObjectNode subnet = subnets.addObject();
            subnet.put("SubnetIdentifier", subnetId);
            subnet.putObject("SubnetAvailabilityZone")
                    .put("Name", group.getSubnetAvailabilityZones().get(subnetId));
            subnet.put("SubnetStatus", "Active");
        });
        ArrayNode networkTypes = node.putArray("SupportedNetworkTypes");
        group.getSupportedNetworkTypes().forEach(networkTypes::add);
        // Always false: a read-only group is one DMS manages for a zero-ETL integration, which
        // Floci does not emulate, so every group here is caller-owned and modifiable.
        node.put("IsReadOnly", false);
        return node;
    }
}
